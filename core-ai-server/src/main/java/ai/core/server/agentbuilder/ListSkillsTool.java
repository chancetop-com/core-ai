package ai.core.server.agentbuilder;

import ai.core.agent.ExecutionContext;
import ai.core.server.skill.SkillService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;

import java.util.Map;

public final class ListSkillsTool extends ToolCall {
    public static final String TOOL_NAME = "list_skills";

    public static ListSkillsTool create(SkillService skillService) {
        var tool = new ListSkillsTool(skillService);
        new Builder(tool).build();
        return tool;
    }

    private final SkillService skillService;

    private ListSkillsTool(SkillService skillService) {
        this.skillService = skillService;
    }

    @Override
    public ToolCallResult execute(String text) {
        return execute(text, null);
    }

    @Override
    public ToolCallResult execute(String text, ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        try {
            var args = JSON.fromJSON(Map.class, text);

            var query = (String) args.get("query");
            var namespace = (String) args.get("namespace");

            var pageObj = args.get("page");
            var limitObj = args.get("limit");

            int page = pageObj instanceof Number number ? Math.max(1, number.intValue()) : 1;
            int limit = limitObj instanceof Number number ? Math.min(Math.max(1, number.intValue()), 100) : 20;
            int offset = (page - 1) * limit;

            var skills = skillService.list(namespace, null, null, query, null, offset, limit);
            long total = skillService.count(namespace, null, null, query, null);

            var result = new StringBuilder(512);
            result.append("Found ").append(total).append(" skill(s)");
            int totalPages = (int) Math.ceil((double) total / limit);
            result.append(", page ").append(page).append(" of ").append(totalPages);
            result.append(":\n\n");

            if (skills.isEmpty()) {
                result.append("No skills found matching the query.");
            } else {
                for (int i = 0; i < skills.size(); i++) {
                    var skill = skills.get(i);
                    result.append(i + 1).append(". ");
                    result.append(skill.name).append(" (").append(skill.qualifiedName).append(")\n");
                    result.append("   ID: ").append(skill.id).append("\n");
                    if (skill.description != null && !skill.description.isBlank()) {
                        result.append("   Description: ").append(skill.description).append("\n");
                    }
                    result.append("   Namespace: ").append(skill.namespace)
                        .append(" | Source: ").append(skill.sourceType != null ? skill.sourceType.name() : "unknown");
                    if (skill.version != null) {
                        result.append(" | Version: ").append(skill.version);
                    }
                    result.append("\n");
                    if (skill.allowedTools != null && !skill.allowedTools.isEmpty()) {
                        result.append("   Allowed Tools: ").append(String.join(", ", skill.allowedTools)).append("\n");
                    }
                    result.append("\n");
                }
            }

            return ToolCallResult.completed(result.toString())
                .withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("List skills failed: " + e.getMessage(), e)
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private static class Builder extends ToolCall.Builder<Builder, ListSkillsTool> {
        private final ListSkillsTool tool;

        Builder(ListSkillsTool tool) {
            this.tool = tool;
        }

        @Override
        protected Builder self() {
            return this;
        }

        void build() {
            name(TOOL_NAME);
            description("Query and search the list of available Skills. Skills are reusable prompt templates that can be assigned to an agent. Supports text search, namespace filtering, and pagination.");
            parameters(ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "query", "Search query to filter skills by name or description (optional)"),
                ToolCallParameters.ParamSpec.of(String.class, "namespace", "Filter skills by namespace (optional)"),
                ToolCallParameters.ParamSpec.of(Integer.class, "page", "Page number starting from 1 (optional, default 1)"),
                ToolCallParameters.ParamSpec.of(Integer.class, "limit", "Number of skills per page (optional, default 20, max 100)")
            ));
            build(tool);
        }
    }
}
