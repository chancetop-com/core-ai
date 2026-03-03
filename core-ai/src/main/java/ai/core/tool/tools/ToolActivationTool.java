package ai.core.tool.tools;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @author stephen
 */
public class ToolActivationTool extends ToolCall {
    public static final String TOOL_NAME = "activate_tools";
    static final int CATALOG_MODE_THRESHOLD = 30;
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolActivationTool.class);
    private static final int MAX_DESC_LENGTH = 100;
    private static final int MAX_SEARCH_RESULTS = 20;
    private static final String SEARCH_MODE_DESC = """
            Search and activate additional tools. A large number of tools are available but not shown.
            
            Usage:
            1. Search: activate_tools(query="database connection") — find tools by keyword
            2. Activate: activate_tools(tool_names=["tool_a", "tool_b"]) — make tools available
            
            Search first, then activate the tools you need from the search results.""";

    public static Builder builder() {
        return new Builder();
    }

    static String buildCatalogDescription(List<ToolCall> discoverableTools) {
        var sb = new StringBuilder(256);
        sb.append("""
                Activate additional tools to make them available for use.
                Call this with the names of tools you need, then you can use them in your next response.
                
                Tool Catalog:
                """);
        for (var tool : discoverableTools) {
            sb.append("- ").append(tool.getName()).append(": ").append(truncateDesc(tool.getDescription())).append('\n');
        }
        return sb.toString();
    }

    private static String truncateDesc(String desc) {
        if (desc != null && desc.length() > MAX_DESC_LENGTH) {
            return desc.substring(0, MAX_DESC_LENGTH - 3) + "...";
        }
        return desc;
    }

    private List<ToolCall> allToolCalls;
    private boolean searchMode;

    @Override
    public String getDescription() {
        if (searchMode) {
            return SEARCH_MODE_DESC;
        }
        var inactiveTools = allToolCalls.stream().filter(t -> t.isDiscoverable() && !t.isLlmVisible()).toList();
        return buildCatalogDescription(inactiveTools);
    }

    @Override
    public ToolCallResult execute(String arguments) {
        try {
            var argsMap = JSON.fromJSON(Map.class, arguments);
            @SuppressWarnings("unchecked")
            var toolNames = (List<String>) argsMap.get("tool_names");
            var query = (String) argsMap.get("query");

            if (query != null && !query.isBlank()) {
                return executeSearch(query);
            }
            if (toolNames != null && !toolNames.isEmpty()) {
                return executeActivate(toolNames);
            }
            return ToolCallResult.failed("Provide either 'query' to search or 'tool_names' to activate");
        } catch (Exception e) {
            return ToolCallResult.failed("Failed: " + e.getMessage(), e);
        }
    }

    private ToolCallResult executeSearch(String query) {
        var keywords = query.toLowerCase(Locale.ROOT).split("\\s+");
        var matches = allToolCalls.stream()
                .filter(t -> t.isDiscoverable() && !t.isLlmVisible())
                .filter(t -> matchesAny(t, keywords))
                .limit(MAX_SEARCH_RESULTS)
                .toList();

        if (matches.isEmpty()) {
            return ToolCallResult.completed("No tools found matching: " + query);
        }

        var sb = new StringBuilder(256);
        sb.append("Found ").append(matches.size()).append(" tools:\n");
        for (var tool : matches) {
            sb.append("- ").append(tool.getName()).append(": ").append(truncateDesc(tool.getDescription())).append('\n');
        }
        sb.append("\nCall activate_tools with tool_names to activate the ones you need.");

        LOGGER.debug("activate_tools search query='{}', found={}", query, matches.size());
        return ToolCallResult.completed(sb.toString());
    }

    private boolean matchesAny(ToolCall tool, String[] keywords) {
        var name = tool.getName().toLowerCase(Locale.ROOT);
        var desc = tool.getDescription() != null ? tool.getDescription().toLowerCase(Locale.ROOT) : "";
        for (var kw : keywords) {
            if (name.contains(kw) || desc.contains(kw)) return true;
        }
        return false;
    }

    private ToolCallResult executeActivate(List<String> toolNames) {
        var activated = new ArrayList<String>();
        var notFound = new ArrayList<String>();

        for (var requestedName : toolNames) {
            boolean found = false;
            for (var tool : allToolCalls) {
                if (tool.isDiscoverable() && tool.getName().equalsIgnoreCase(requestedName)) {
                    tool.setLlmVisible(true);
                    activated.add(tool.getName());
                    found = true;
                    break;
                }
            }
            if (!found) {
                notFound.add(requestedName);
            }
        }

        var sb = new StringBuilder(128);
        if (!activated.isEmpty()) {
            sb.append("Activated tools: ").append(String.join(", ", activated))
                    .append(". These tools are now available for you to use.");
        }
        if (!notFound.isEmpty()) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append("Tools not found: ").append(String.join(", ", notFound));
        }

        LOGGER.debug("activate_tools activated={}, notFound={}", activated, notFound);
        return ToolCallResult.completed(sb.toString());
    }

    public static class Builder extends ToolCall.Builder<Builder, ToolActivationTool> {
        private List<ToolCall> allToolCalls;

        public Builder allToolCalls(List<ToolCall> allToolCalls) {
            this.allToolCalls = allToolCalls;
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public ToolActivationTool build() {
            var discoverableCount = allToolCalls.stream().filter(ToolCall::isDiscoverable).count();
            var isSearchMode = discoverableCount > CATALOG_MODE_THRESHOLD;

            this.name(TOOL_NAME);
            this.description(isSearchMode ? SEARCH_MODE_DESC : buildCatalogDescription(allToolCalls.stream().filter(ToolCall::isDiscoverable).toList()));

            var params = new ArrayList<ToolCallParameter>();
            params.add(ToolCallParameter.builder()
                    .name("tool_names")
                    .description("List of tool names to activate")
                    .type(ToolCallParameterType.LIST)
                    .itemType(String.class)
                    .build());
            if (isSearchMode) {
                params.add(ToolCallParameter.builder()
                        .name("query")
                        .description("Search keyword to find tools by name or description")
                        .classType(String.class)
                        .build());
            }
            this.parameters(params);

            var tool = new ToolActivationTool();
            build(tool);
            tool.allToolCalls = this.allToolCalls;
            tool.searchMode = isSearchMode;
            return tool;
        }
    }
}
