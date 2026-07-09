package ai.core.server.agentbuilder;

import ai.core.agent.ExecutionContext;
import ai.core.server.dataset.DatasetService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;

import java.util.Map;

public final class ListDatasetsTool extends ToolCall {
    public static final String TOOL_NAME = "list_datasets";

    public static ListDatasetsTool create(DatasetService datasetService) {
        var tool = new ListDatasetsTool(datasetService);
        new Builder(tool).build();
        return tool;
    }

    private final DatasetService datasetService;

    private ListDatasetsTool(DatasetService datasetService) {
        this.datasetService = datasetService;
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

            var pageObj = args.get("page");
            var limitObj = args.get("limit");

            int page = pageObj instanceof Number number ? Math.max(1, number.intValue()) : 1;
            int limit = limitObj instanceof Number number ? Math.min(Math.max(1, number.intValue()), 100) : 20;
            int offset = (page - 1) * limit;

            var datasets = datasetService.list(query, offset, limit);
            long total = datasetService.count(query);

            var result = new StringBuilder(512);
            result.append("Found ").append(total).append(" dataset(s)");
            int totalPages = (int) Math.ceil((double) total / limit);
            result.append(", page ").append(page).append(" of ").append(totalPages);
            result.append(":\n\n");

            if (datasets.isEmpty()) {
                result.append("No datasets found matching the query.");
            } else {
                for (int i = 0; i < datasets.size(); i++) {
                    var dataset = datasets.get(i);
                    result.append(i + 1).append(". ");
                    result.append(dataset.name).append(" (").append(dataset.id).append(")\n");
                    if (dataset.description != null && !dataset.description.isBlank()) {
                        result.append("   Description: ").append(dataset.description).append("\n");
                    }
                    result.append("   Created by: ").append(dataset.userId).append("\n");
                    if (dataset.schema != null && !dataset.schema.isEmpty()) {
                        result.append("   Schema:\n");
                        for (var field : dataset.schema) {
                            result.append("     - ").append(field.name);
                            if (field.label != null) result.append(" (").append(field.label).append(")");
                            result.append(": ").append(field.type != null ? field.type.name() : "unknown");
                            result.append("\n");
                        }
                    }
                    result.append("\n");
                }
            }

            return ToolCallResult.completed(result.toString())
                .withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("List datasets failed: " + e.getMessage(), e)
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private static class Builder extends ToolCall.Builder<Builder, ListDatasetsTool> {
        private final ListDatasetsTool tool;

        Builder(ListDatasetsTool tool) {
            this.tool = tool;
        }

        @Override
        protected Builder self() {
            return this;
        }

        void build() {
            name(TOOL_NAME);
            description("Query and search the list of available Datasets. Datasets are structured data stores that can be assigned to an agent for reading or writing data. Supports text search and pagination.");
            parameters(ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "query", "Search query to filter datasets by name or description (optional)"),
                ToolCallParameters.ParamSpec.of(Integer.class, "page", "Page number starting from 1 (optional, default 1)"),
                ToolCallParameters.ParamSpec.of(Integer.class, "limit", "Number of datasets per page (optional, default 20, max 100)")
            ));
            build(tool);
        }
    }
}
