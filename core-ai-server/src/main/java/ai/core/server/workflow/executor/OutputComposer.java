package ai.core.server.workflow.executor;

import ai.core.server.domain.ArtifactRef;
import ai.core.server.workflow.ArtifactStaging;
import ai.core.server.workflow.NodeContext;
import ai.core.server.workflow.engine.WorkflowEdge;
import ai.core.server.workflow.engine.WorkflowGraph;
import core.framework.json.JSON;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared output composition for the terminal (END) and mid-graph (AGGREGATOR) nodes — the same operation in two
 * positions. Rule:
 * <ol>
 *   <li>an explicit {@code output} template in the config -> render it over the variable pool (the recommended
 *       path; e.g. a prompt-shaped string to feed a downstream LLM);</li>
 *   <li>otherwise combine this node's COMPLETED immediate predecessors (the pool holds only completed outputs):
 *       exactly one -> pass it through unwrapped (covers linear flow and conditional coalesce — only one branch
 *       runs); several (a parallel join) -> merge into a JSON object keyed by predecessor node id.</li>
 * </ol>
 * The merge is a best-effort scaffold; its shape varies with how many predecessors complete, so an explicit
 * template is preferred whenever a node has several concurrent inputs.
 *
 * @author Xander
 */
public final class OutputComposer {
    private static final Pattern ARTIFACTS_SELECTOR = Pattern.compile("(?:\\{\\{\\s*)?nodes\\.([A-Za-z_][A-Za-z0-9_]*)\\.artifacts(?:\\s*}})?");

    private OutputComposer() {
    }

    public static String compose(NodeContext ctx) {
        Object template = ctx.node().config().get("output");
        if (template instanceof String text && !text.isBlank()) {
            return ctx.pool().render(text);
        }
        // gather the raw output of each COMPLETED immediate predecessor (resolve() returns empty for skipped ones)
        var completed = new LinkedHashMap<String, String>();
        for (String predId : predecessorIds(ctx)) {
            ctx.pool().resolve("nodes." + predId + ".output").ifPresent(value_ -> completed.put(predId, String.valueOf(value_)));
        }
        if (completed.isEmpty()) {
            return "{}";   // a fired node always has >=1 completed predecessor; defensive only
        }
        if (completed.size() == 1) {
            return completed.values().iterator().next();   // single -> pass-through, unchanged
        }
        var merged = new LinkedHashMap<String, Object>();
        completed.forEach((id, output) -> merged.put(id, parse(output)));
        return JSON.toJSON(merged);
    }

    /**
     * The artifacts this node produces for downstream / delivery: the union of its COMPLETED immediate
     * predecessors' files PLUS any files its own {@code output} template references via
     * {@code {{ nodes.<id>.artifacts }}} (file-consuming granularity, see {@link ArtifactStaging#referencedFiles}),
     * de-duplicated by file_id. The output-template lift lives here — the operation END and AGGREGATOR share — so
     * a file an aggregator's output references propagates downstream instead of being dropped at its boundary, and
     * END's default deliverables behave the same. Mirrors how {@link #compose} coalesces their outputs.
     */
    public static List<ArtifactRef> composeArtifacts(NodeContext ctx) {
        var lists = new ArrayList<List<ArtifactRef>>();
        for (String predId : predecessorIds(ctx)) {
            lists.add(ctx.pool().artifactsOf(predId));
        }
        if (ctx.node().config().get("output") instanceof String output) {
            lists.add(ArtifactStaging.referencedFiles(output, ctx.pool()));
        }
        return ArtifactRef.union(lists);
    }

    /**
     * The deliverable files of a terminal node. An explicit {@code artifacts} selector list in the config (each
     * entry {@code nodes.<id>.artifacts}) is AUTHORITATIVE — exactly those nodes' files are delivered, so a user
     * can narrow the set even while the {@code output} template shows other files as text. With no explicit list,
     * default to {@link #composeArtifacts}: the immediate predecessors' files plus whatever the output template
     * references (the auto-lift), so an upstream node's files don't surface only as result text. De-duplicated by
     * file_id. Non-artifact selector entries are ignored — the dominator check already rejected unreadable nodes
     * at publish time.
     */
    public static List<ArtifactRef> composeDeliverables(NodeContext ctx) {
        Object declared = ctx.node().config().get("artifacts");
        if (!(declared instanceof List<?> selectors) || selectors.isEmpty()) {
            return defaultDeliverables(ctx);
        }
        var groups = new ArrayList<ArtifactGroup>();
        int sequence = 0;
        for (Object entry : selectors) {
            if (entry instanceof String selector) {
                Optional<String> nodeId = artifactsNodeId(selector);
                if (nodeId.isPresent()) {
                    groups.add(new ArtifactGroup(nodeId.get(), ctx.pool().artifactsOf(nodeId.get()), sequence));
                }
            }
            sequence++;
        }
        return unionInFlowOrder(ctx, groups);
    }

    private static List<ArtifactRef> defaultDeliverables(NodeContext ctx) {
        var groups = new ArrayList<ArtifactGroup>();
        int sequence = 0;
        for (String predId : predecessorIds(ctx)) {
            groups.add(new ArtifactGroup(predId, ctx.pool().artifactsOf(predId), sequence++));
        }
        if (ctx.node().config().get("output") instanceof String output) {
            for (ArtifactStaging.ReferencedFiles refs : ArtifactStaging.referencedFileGroups(output, ctx.pool())) {
                groups.add(new ArtifactGroup(refs.nodeId(), refs.artifacts(), sequence++));
            }
        }
        return unionInFlowOrder(ctx, groups);
    }

    private static List<ArtifactRef> unionInFlowOrder(NodeContext ctx, List<ArtifactGroup> groups) {
        Map<String, Integer> ranks = flowRanks(ctx.graph());
        groups.sort(Comparator
            .comparingInt((ArtifactGroup group) -> ranks.getOrDefault(group.nodeId(), Integer.MAX_VALUE))
            .thenComparingInt(ArtifactGroup::sequence));

        var seen = new LinkedHashSet<String>();
        var merged = new ArrayList<ArtifactRef>();
        for (ArtifactGroup group : groups) {
            if (group.artifacts() == null) continue;
            for (ArtifactRef ref : group.artifacts()) {
                if (ref.fileId == null || seen.add(ref.fileId)) merged.add(ref);
            }
        }
        return merged;
    }

    private static Map<String, Integer> flowRanks(WorkflowGraph graph) {
        var indegree = new LinkedHashMap<String, Integer>();
        var outgoing = new LinkedHashMap<String, List<String>>();
        for (var node : graph.nodes()) {
            indegree.put(node.id(), 0);
            outgoing.put(node.id(), new ArrayList<>());
        }
        for (WorkflowEdge edge : graph.edges()) {
            if (!outgoing.containsKey(edge.source()) || !indegree.containsKey(edge.target())) continue;
            outgoing.get(edge.source()).add(edge.target());
            indegree.put(edge.target(), indegree.get(edge.target()) + 1);
        }

        var queue = new ArrayDeque<String>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        var ranks = new LinkedHashMap<String, Integer>();
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            if (ranks.containsKey(id)) continue;
            ranks.put(id, ranks.size());
            for (String target : outgoing.getOrDefault(id, List.of())) {
                int next = indegree.get(target) - 1;
                indegree.put(target, next);
                if (next == 0) queue.add(target);
            }
        }
        for (String id : indegree.keySet()) {
            ranks.putIfAbsent(id, ranks.size());
        }
        return ranks;
    }

    private record ArtifactGroup(String nodeId, List<ArtifactRef> artifacts, int sequence) {
    }

    // "nodes.<id>.artifacts" (with optional surrounding {{ }} the editor may emit) -> the source node id.
    private static Optional<String> artifactsNodeId(String selector) {
        Matcher matcher = ARTIFACTS_SELECTOR.matcher(selector.trim());
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static List<String> predecessorIds(NodeContext ctx) {
        return ctx.graph().inEdges(ctx.node().id()).stream().map(WorkflowEdge::source).distinct().toList();
    }

    // Parse an object output so the merge nests cleanly (no double-encoding); leave scalars/text/lists as the raw string.
    private static Object parse(String output) {
        try {
            return JSON.fromJSON(Map.class, output);
        } catch (RuntimeException e) {
            return output;
        }
    }
}
