package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.session.CustomEventEmitter;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillRegistry;
import ai.core.skill.SkillSdkCapabilityManifest;
import ai.core.skill.SkillSearchScorer;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.tool.ToolSearchScorer;
import ai.core.utils.JsonUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Read-only skill directory search tool. Returns metadata summaries only (qualifiedName,
 * description summary, tier, namespace, attached flag) and never grants session authorization
 * or modifies allowed skill ids.
 *
 * @author stephen
 */
public class SearchSkillsTool extends ToolCall {
    public static final String TOOL_NAME = "search_skills";
    public static final int DEFAULT_LIMIT = 8;
    public static final int MAX_LIMIT = 20;
    private static final int MAX_SUMMARY_CHARS = 180;
    private static final String DEGRADED_MESSAGE =
            "Skill catalog is temporarily unavailable due to a server refresh error; proceed with currently attached skills.";

    private static final String DESC = """
            Search available skills by keyword to discover relevant workflows.
            Returns directory metadata only and does NOT attach or authorize skills automatically.
            If a matching skill is already attached, call use_skill(name). If not attached, ask the user to attach it first.
            """;

    public static Builder builder() {
        return new Builder();
    }

    public static SearchBackend attachedOnlyBackend(SkillRegistry registry) {
        return (query, namespaceFilter, limit) -> searchAttached(registry, query, namespaceFilter, limit);
    }

    private static SearchResponse searchAttached(SkillRegistry registry, String query, String namespaceFilter, int limit) {
        if (registry == null) return new SearchResponse(List.of(), List.of(), false);
        var tokens = ToolSearchScorer.tokenize(query);
        var hits = new ArrayList<ScoredLocal>();
        for (var skill : registry.listAll()) {
            if (namespaceFilter != null && !namespaceFilter.isBlank()
                    && !namespaceFilter.equals(skill.getNamespace())) {
                continue;
            }
            boolean coreEligible = "CORE".equalsIgnoreCase(skill.getTier())
                    || "ALWAYS_ON".equalsIgnoreCase(skill.getTier());
            var candidate = new SkillSearchScorer.Candidate(
                    skill.getNamespace(),
                    skill.getName(),
                    skill.getDescription(),
                    skill.getAllowedTools(),
                    skill.getMetadata(),
                    coreEligible);
            Integer score = tokens.isEmpty() ? 0 : SkillSearchScorer.score(candidate, tokens, query);
            if (score != null) {
                hits.add(new ScoredLocal(skill, score));
            }
        }
        hits.sort(Comparator.comparingInt(ScoredLocal::score).reversed()
                .thenComparing(hit -> hit.skill().getQualifiedName()));
        var results = hits.stream()
                .limit(limit)
                .map(hit -> toAttachedHit(hit.skill()))
                .toList();
        return new SearchResponse(results, List.of(), false);
    }

    private static SearchHit toAttachedHit(SkillMetadata skill) {
        var manifest = SkillSdkCapabilityManifest.parse(skill.getAllowedTools(), skill.getMetadata());
        var capabilities = manifest.entries().stream()
                .map(e -> e.kind() + ":" + e.displayPath())
                .toList();
        return new SearchHit(
                skill.getQualifiedName(),
                truncate(skill.getDescription()),
                skill.getTier() != null ? skill.getTier() : "DOMAIN",
                skill.getNamespace() != null ? skill.getNamespace() : "",
                true,
                capabilities);
    }

    private static String truncate(String text) {
        if (text == null) return "";
        var trimmed = text.trim();
        if (trimmed.length() <= MAX_SUMMARY_CHARS) return trimmed;
        return trimmed.substring(0, MAX_SUMMARY_CHARS - 3) + "...";
    }

    private SearchBackend backend;

    @Override
    public ToolCallResult execute(String arguments) {
        return execute(arguments, null);
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        long start = System.currentTimeMillis();
        try {
            var args = parseArguments(arguments);
            var query = getStringValue(args, "query");
            var namespace = getStringValue(args, "namespace");
            var limit = normalizeLimit(getIntValue(args, "limit"));
            if (query == null || query.isBlank()) {
                return ToolCallResult.failed("'query' is required")
                        .withDuration(System.currentTimeMillis() - start);
            }
            var response = backend.search(query.trim(), namespace, limit);
            if (response.degraded()) {
                return ToolCallResult.completed(DEGRADED_MESSAGE)
                        .withDuration(System.currentTimeMillis() - start);
            }
            emitUnattachedCards(context, response.hits());
            return ToolCallResult.completed(render(query.trim(), response))
                    .withDuration(System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolCallResult.failed("Failed: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - start);
        }
    }

    private void emitUnattachedCards(ExecutionContext context, List<SearchHit> hits) {
        if (context == null || hits == null || hits.isEmpty()) return;
        var emitter = context.getCustomVariable(CustomEventEmitter.CONTEXT_KEY, CustomEventEmitter.class);
        if (emitter == null) return;
        for (var hit : hits) {
            if (hit.attached()) continue;
            var items = new ArrayList<Map<String, String>>(4);
            items.add(Map.of("label", "Skill", "value", hit.qualifiedName()));
            items.add(Map.of("label", "Tier", "value", hit.tier()));
            items.add(Map.of("label", "Status", "value", "Not attached to this session"));
            if (hit.requiredCapabilities() != null && !hit.requiredCapabilities().isEmpty()) {
                items.add(Map.of("label", "Required Capabilities", "value", String.join(", ", hit.requiredCapabilities())));
            }
            var card = JsonUtil.toJson(Map.of(
                    "schema_version", 1,
                    "title", "Skill Not Attached · " + hit.qualifiedName(),
                    "blocks", List.of(Map.of("type", "key_values", "items", items))));
            var data = JsonUtil.toJson(Map.of(
                    "qualified_name", hit.qualifiedName(),
                    "tier", hit.tier(),
                    "namespace", hit.namespace(),
                    "attached", Boolean.FALSE,
                    "description", hit.description(),
                    "required_capabilities", hit.requiredCapabilities() == null ? List.of() : hit.requiredCapabilities()));
            emitter.emit("skill_attach_request", data, card, context.getCurrentToolCallId());
        }
    }

    private String render(String query, SearchResponse response) {
        if (response.hits() == null || response.hits().isEmpty()) {
            return "No skills found matching: " + query;
        }
        var sb = new StringBuilder(256);
        sb.append("Found ").append(response.hits().size()).append(" skill(s):\n");
        if (response.namespaces() != null && !response.namespaces().isEmpty()) {
            sb.append("Matched namespaces: ");
            for (int i = 0; i < response.namespaces().size(); i++) {
                if (i > 0) sb.append(", ");
                var ns = response.namespaces().get(i);
                sb.append(ns.namespace()).append(" (").append(ns.matchedCount()).append(')');
            }
            sb.append('\n');
        }
        for (var hit : response.hits()) {
            sb.append("- ").append(hit.qualifiedName())
                    .append(" [tier=").append(hit.tier())
                    .append(", namespace=").append(hit.namespace())
                    .append(", attached=").append(hit.attached()).append("]: ")
                    .append(truncate(hit.description())).append('\n');
            if (!hit.attached()) {
                sb.append("  Not attached to this session. Ask the user to attach '")
                        .append(hit.qualifiedName())
                        .append("' before calling use_skill.\n");
            }
        }
        return sb.toString();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    private Integer getIntValue(Map<String, Object> args, String key) {
        var value = args.get(key);
        if (value instanceof Number num) return num.intValue();
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @FunctionalInterface
    public interface SearchBackend {
        SearchResponse search(String query, String namespaceFilter, int limit);
    }

    public record SearchHit(
            String qualifiedName,
            String description,
            String tier,
            String namespace,
            boolean attached,
            List<String> requiredCapabilities
    ) {
    }

    public record NamespaceSummary(String namespace, int matchedCount) {
    }

    public record SearchResponse(List<SearchHit> hits, List<NamespaceSummary> namespaces, boolean degraded) {
    }

    private record ScoredLocal(SkillMetadata skill, int score) {
    }

    public static class Builder extends ToolCall.Builder<Builder, SearchSkillsTool> {
        private SearchBackend backend;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder backend(SearchBackend backend) {
            this.backend = backend;
            return this;
        }

        public SearchSkillsTool build() {
            if (backend == null) {
                throw new IllegalStateException("backend is required");
            }
            this.name(TOOL_NAME);
            this.description(DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "query", "Keyword query to search skills").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "namespace", "Optional namespace filter"),
                    ToolCallParameters.ParamSpec.of(Integer.class, "limit", "Maximum results (default 8, max 20)")
            ));
            var tool = new SearchSkillsTool();
            build(tool);
            tool.backend = this.backend;
            return tool;
        }
    }
}
