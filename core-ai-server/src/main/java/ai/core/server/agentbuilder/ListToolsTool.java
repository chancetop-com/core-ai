package ai.core.server.agentbuilder;

import ai.core.agent.ExecutionContext;
import ai.core.server.tool.ToolRegistryService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;

import java.util.Map;

public final class ListToolsTool extends ToolCall {
    public static final String TOOL_NAME = "list_tools";

    public static ListToolsTool create(ToolRegistryService toolRegistryService) {
        var tool = new ListToolsTool(toolRegistryService);
        new Builder(tool).build();
        return tool;
    }

    private final ToolRegistryService toolRegistryService;

    private ListToolsTool(ToolRegistryService toolRegistryService) {
        this.toolRegistryService = toolRegistryService;
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
            var category = (String) args.get("category");

            var tools = toolRegistryService.listTools(category);

            var result = new StringBuilder(512);
            result.append("Found ").append(tools.size()).append(" tool(s)");
            if (category != null && !category.isBlank()) {
                result.append(" in category '").append(category).append("'");
            }
            result.append(":\n\n");

            if (tools.isEmpty()) {
                result.append("No tools found matching the criteria.");
            } else {
                // Group by category for better readability when no filter
                Map<String, java.util.List<ai.core.server.domain.ToolRegistryEntry>> grouped = null;
                if (category == null || category.isBlank()) {
                    grouped = tools.stream()
                        .collect(java.util.stream.Collectors.groupingBy(
                            t -> t.category != null ? t.category : "uncategorized",
                            java.util.stream.Collectors.toList()
                        ));
                }

                if (grouped != null) {
                    var sortedCategories = new java.util.ArrayList<>(grouped.keySet());
                    java.util.Collections.sort(sortedCategories);
                    int globalIndex = 0;
                    for (var cat : sortedCategories) {
                        var catTools = grouped.get(cat);
                        result.append("--- Category: ").append(cat).append(" ---\n");
                        for (var tool : catTools) {
                            globalIndex++;
                            result.append(globalIndex).append(". ");
                            result.append(tool.id);
                            if (tool.name != null && !tool.name.equals(tool.id)) {
                                result.append(" (").append(tool.name).append(")");
                            }
                            result.append("\n");
                            if (tool.description != null && !tool.description.isBlank()) {
                                result.append("   Description: ").append(tool.description).append("\n");
                            }
                            result.append("   Type: ").append(tool.type != null ? tool.type.name() : "unknown");
                            result.append(" | Enabled: ").append(Boolean.TRUE.equals(tool.enabled));
                            result.append("\n\n");
                        }
                    }
                } else {
                    for (int i = 0; i < tools.size(); i++) {
                        var tool = tools.get(i);
                        result.append(i + 1).append(". ");
                        result.append(tool.id);
                        if (tool.name != null && !tool.name.equals(tool.id)) {
                            result.append(" (").append(tool.name).append(")");
                        }
                        result.append("\n");
                        if (tool.description != null && !tool.description.isBlank()) {
                            result.append("   Description: ").append(tool.description).append("\n");
                        }
                        result.append("   Type: ").append(tool.type != null ? tool.type.name() : "unknown");
                        result.append(" | Category: ").append(tool.category != null ? tool.category : "");
                        result.append(" | Enabled: ").append(Boolean.TRUE.equals(tool.enabled));
                        result.append("\n\n");
                    }
                }
            }

            return ToolCallResult.completed(result.toString())
                .withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("List tools failed: " + e.getMessage(), e)
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private static class Builder extends ToolCall.Builder<Builder, ListToolsTool> {
        private final ListToolsTool tool;

        Builder(ListToolsTool tool) {
            this.tool = tool;
        }

        @Override
        protected Builder self() {
            return this;
        }

        void build() {
            name(TOOL_NAME);
            description("List all available tools registered in the system. Tools can be of type BUILTIN, MCP, or API. Optionally filter by category to narrow down the results.");
            parameters(ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "category", "Filter tools by category (optional). Common categories: 'builtin', 'config', or custom MCP server categories. Omit to list all tools.")
            ));
            build(tool);
        }
    }
}
