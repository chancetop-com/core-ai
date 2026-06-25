package ai.core.server.agentbuilder;

import ai.core.agent.ExecutionContext;
import ai.core.api.server.agent.ListAgentsRequest;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;

import java.util.Map;

/**
 * @author stephen
 */
public final class ListAgentsTool extends ToolCall {
    public static final String TOOL_NAME = "list_agents";

    public static ListAgentsTool create(AgentDefinitionService agentDefinitionService) {
        var tool = new ListAgentsTool(agentDefinitionService);
        new Builder(tool).build();
        return tool;
    }

    private final AgentDefinitionService agentDefinitionService;

    private ListAgentsTool(AgentDefinitionService agentDefinitionService) {
        this.agentDefinitionService = agentDefinitionService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolCallResult execute(String text) {
        return execute(text, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolCallResult execute(String text, ExecutionContext context) {
        var userId = context != null ? context.getUserId() : null;
        return doExecute(text, userId != null ? userId : "system");
    }

    @SuppressWarnings({"unchecked", "PMD.ConsecutiveLiteralAppends", "PMD.AppendCharacterWithChar"})
    private ToolCallResult doExecute(String text, String userId) {
        long startTime = System.currentTimeMillis();
        try {
            var args = JSON.fromJSON(Map.class, text);
            var request = new ListAgentsRequest();
            request.query = (String) args.get("query");
            request.myAgents = (String) args.get("my");
            request.sort = (String) args.get("sort");

            var pageObj = args.get("page");
            if (pageObj instanceof Number number) {
                request.page = number.intValue();
            }

            var limitObj = args.get("limit");
            if (limitObj instanceof Number number) {
                request.limit = number.intValue();
            }

            var includeDefaultObj = args.get("include_system_default");
            if (includeDefaultObj instanceof Boolean b) {
                request.includeSystemDefault = b;
            }

            var response = agentDefinitionService.list(userId, request);

            var result = new StringBuilder(512);
            result.append("Found ").append(response.total).append(" agent(s)");
            if (response.page != null && response.limit != null) {
                int totalPages = (int) Math.ceil((double) response.total / response.limit);
                result.append(", page ").append(response.page).append(" of ").append(totalPages);
            }
            result.append(":\n\n");

            if (response.agents.isEmpty()) {
                result.append("No agents found matching the query.");
            } else {
                for (int i = 0; i < response.agents.size(); i++) {
                    var agent = response.agents.get(i);
                    result.append(i + 1).append(". ");
                    result.append(agent.name).append(" (").append(agent.id).append(")\n");
                    if (agent.description != null && !agent.description.isBlank()) {
                        result.append("   Description: ").append(agent.description).append("\n");
                    }
                    result.append("   Status: ").append(agent.status)
                        .append(" | Type: ").append(agent.type);
                    if (agent.createdBy != null) {
                        result.append(" | Created by: ").append(agent.createdBy);
                    }
                    result.append("\n");
                    if (agent.model != null) {
                        result.append("   Model: ").append(agent.model).append("\n");
                    }
                    result.append("\n");
                }
            }

            return ToolCallResult.completed(result.toString())
                .withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("List agents failed: " + e.getMessage(), e)
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private static class Builder extends ToolCall.Builder<Builder, ListAgentsTool> {
        private final ListAgentsTool tool;

        Builder(ListAgentsTool tool) {
            this.tool = tool;
        }

        @Override
        protected Builder self() {
            return this;
        }

        void build() {
            name(TOOL_NAME);
            description("Query and search the list of agents. Supports text search on name and description, filtering by my agents or others, and pagination.");
            parameters(ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "query", "Search query to filter agents by name or description (optional)"),
                ToolCallParameters.ParamSpec.of(String.class, "my", "Filter: 'true' for my agents, 'false' for others' agents (optional, omit for all)"),
                ToolCallParameters.ParamSpec.of(Integer.class, "page", "Page number starting from 1 (optional, default 1)"),
                ToolCallParameters.ParamSpec.of(Integer.class, "limit", "Number of agents per page (optional, default 20, max 200)"),
                ToolCallParameters.ParamSpec.of(String.class, "sort", "Sort by field: 'updated_at' (default) or 'created_at' (optional)"),
                ToolCallParameters.ParamSpec.of(Boolean.class, "include_system_default", "Include system-default agents when filtering my agents (optional, default false)")
            ));
            build(tool);
        }
    }
}
