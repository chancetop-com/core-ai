package ai.core.server.memory;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;

import java.util.List;

/**
 * On-demand memory search tool for the V2 three-tier memory architecture.
 * <p>
 * Allows the agent to query Layer 2 (methods: WORKFLOW_PATTERN, TOOL_USAGE, EFFICIENCY)
 * and Layer 3 (trajectories: session summaries) on demand. Layer 1 (knowledge) is
 * auto-injected and not searchable via this tool.
 * <p>
 * The agent should use this tool when it needs historical context or reusable patterns
 * that are NOT in the SOP. SOP always takes priority over any search result.
 *
 * @author stephen
 */
public final class SearchMemoryTool extends ToolCall {
    static final String TOOL_NAME = "search_memory";
    private static final String TOOL_DESC = """
            Search the agent's memory for historical patterns and past session summaries.

            Use this tool when you need context from previous runs — for example, how a
            similar task was handled before, what tool configurations worked, or what
            pitfalls to avoid. Memory is supplementary: the current skill SOP is your
            primary behavior guide and always takes priority.

            - Layer "methods": reusable patterns (workflow patterns, tool usage tips,
              efficiency shortcuts) from past successful runs.
            - Layer "trajectories": raw session summaries — what happened in specific
              past sessions, faithfully recorded.
            - Layer "all": search both layers.

            Results are returned with their source layer so you know whether a result
            is a distilled pattern or a raw session record.
            """;

    private static List<ToolCallParameter> parameters() {
        return ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "query", "Natural language search query (e.g. \"restaurant SEO keyword strategy\", \"API authentication pattern\")").required(),
                ToolCallParameters.ParamSpec.of(String.class, "layer", "Memory layer to search: 'methods' (extracted patterns), 'trajectories' (session summaries), or 'all' (default)")
                        .optional()
                        .enums(List.of("methods", "trajectories", "all")),
                ToolCallParameters.ParamSpec.of(Integer.class, "limit", "Maximum results to return (default 5)")
                        .optional()
        );
    }

    private final String agentId;
    private final AgentMemoryService agentMemoryService;

    public SearchMemoryTool(String agentId, AgentMemoryService agentMemoryService) {
        this.agentId = agentId;
        this.agentMemoryService = agentMemoryService;
        setName(TOOL_NAME);
        setDescription(TOOL_DESC);
        setParameters(parameters());
        setNeedAuth(Boolean.FALSE);
        setDirectReturn(Boolean.FALSE);
        setLlmVisible(Boolean.TRUE);
        setDiscoverable(Boolean.FALSE);
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return ToolCallResult.failed("search_memory requires ExecutionContext; direct execute is not supported");
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        var args = parseArguments(arguments);
        var query = getStringValue(args, "query");
        if (query == null || query.isBlank()) {
            return ToolCallResult.failed("query is required");
        }

        var layer = getStringValue(args, "layer");
        if (layer == null || layer.isBlank()) {
            layer = "all";
        }

        var limit = 5;
        var limitObj = args.get("limit");
        if (limitObj instanceof Number num) {
            limit = num.intValue();
        }

        var results = agentMemoryService.searchMemories(agentId, query, layer, limit);

        if (results.isEmpty()) {
            return ToolCallResult.completed("No matching memories found.");
        }

        var formatted = formatResults(results);
        return ToolCallResult.completed(formatted);
    }

    private String formatResults(List<AgentMemory> results) {
        var sb = new StringBuilder(128);
        sb.append("Found ").append(results.size()).append(" matching memories:\n\n");
        for (int i = 0; i < results.size(); i++) {
            var m = results.get(i);
            sb.append("--- Memory ").append(i + 1).append(" ---\nLayer: ").append(m.layer).append('\n');
            if (m.type != null) {
                sb.append("Type: ").append(m.type).append('\n');
            }
            sb.append("Content: ").append(m.content).append('\n');
            if (m.createdAt != null) {
                sb.append("Recorded: ").append(m.createdAt).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
