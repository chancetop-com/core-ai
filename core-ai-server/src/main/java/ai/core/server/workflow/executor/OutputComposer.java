package ai.core.server.workflow.executor;

import ai.core.server.domain.ArtifactRef;
import ai.core.server.workflow.NodeContext;
import ai.core.server.workflow.engine.WorkflowEdge;
import core.framework.json.JSON;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * Union the artifact references of this node's COMPLETED immediate predecessors, de-duplicated by file_id.
     * Lets an AGGREGATOR coalesce parallel branches' files into one {@code nodes.<id>.artifacts} a downstream
     * node can reference (mirrors how {@link #compose} coalesces their outputs).
     */
    public static List<ArtifactRef> composeArtifacts(NodeContext ctx) {
        var lists = new ArrayList<List<ArtifactRef>>();
        for (String predId : predecessorIds(ctx)) {
            lists.add(ctx.pool().artifactsOf(predId));
        }
        return ArtifactRef.union(lists);
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
