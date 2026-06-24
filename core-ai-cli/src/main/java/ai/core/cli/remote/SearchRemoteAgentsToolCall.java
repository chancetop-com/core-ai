package ai.core.cli.remote;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.tool.ToolCallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Searches remote agents by capability summary without exposing their internal tools.
 *
 * @author xander
 */
public class SearchRemoteAgentsToolCall extends ToolCall {
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchRemoteAgentsToolCall.class);
    public static final String TOOL_NAME = "search_remote_agents";
    private static final int SERVER_SIDE_SEARCH_LIMIT = 20;

    public static Builder builder() {
        return new Builder();
    }

    private RemoteAgentCatalog catalog;
    private A2ARemoteAgentDiscovery discovery;
    private List<A2ARemoteServerConfig> serverConfigs;

    @Override
    public ToolCallResult execute(String arguments) {
        var args = parseArguments(arguments);
        var query = getStringValue(args, "query");

        List<RemoteAgentCatalogEntry> matches;
        if (query != null && !query.isBlank()) {
            matches = searchServerSide(query);
        } else {
            matches = catalog.entries();
        }

        return ToolCallResult.completed(format(query, matches))
                .withToolName(getName())
                .withStats("remote_agent_count", catalog.size())
                .withStats("remote_server_count", catalog.serverCount())
                .withStats("remote_agent_matches", matches.size());
    }

    private List<RemoteAgentCatalogEntry> searchServerSide(String query) {
        var entries = new ArrayList<RemoteAgentCatalogEntry>();
        var connectionKeys = new HashSet<String>();
        for (var server : serverConfigs) {
            if (server == null || !server.enabled) continue;
            try {
                for (var config : discovery.searchOnServer(server, query, SERVER_SIDE_SEARCH_LIMIT)) {
                    discovery.addEntry(entries, connectionKeys, config);
                }
            } catch (Exception e) {
                LOGGER.warn("server-side agent search failed for server '{}': {}", server.id, e.getMessage());
            }
        }
        return List.copyOf(entries);
    }

    private String format(String query, List<RemoteAgentCatalogEntry> matches) {
        if (catalog.isEmpty()) return "No remote agents are configured or currently discoverable.";
        if (matches.isEmpty()) {
            if (query != null && !query.isBlank()) {
                return "No remote agents found matching: " + query
                        + ". Use search_remote_agents without a query to list all available agents.";
            }
            return "No remote agents found.";
        }
        var sb = new StringBuilder(256);
        sb.append("Found ").append(matches.size()).append(" remote agents from ")
                .append(catalog.serverCount()).append(" remote servers");
        if (query != null && !query.isBlank()) sb.append(" matching: ").append(query);
        sb.append('\n');
        for (var entry : matches) {
            appendLine(sb, "- " + entry.id());
            appendLine(sb, "  name: " + entry.name());
            if (entry.status() != null && !entry.status().isBlank()) {
                appendLine(sb, "  status: " + entry.status());
            }
            appendLine(sb, "  description: " + description(entry));
        }
        sb.append("\nCall delegate_to_remote_agent with agent_id and task to delegate work.");
        return sb.toString();
    }

    private void appendLine(StringBuilder sb, String line) {
        sb.append(line).append('\n');
    }

    private String description(RemoteAgentCatalogEntry entry) {
        return entry.description() != null && !entry.description().isBlank()
                ? entry.description()
                : "No capability summary provided.";
    }

    public static class Builder extends ToolCall.Builder<Builder, SearchRemoteAgentsToolCall> {
        private RemoteAgentCatalog catalog;
        private A2ARemoteAgentDiscovery discovery;
        private List<A2ARemoteServerConfig> serverConfigs;

        public Builder catalog(RemoteAgentCatalog catalog) {
            this.catalog = catalog;
            return this;
        }

        public Builder discovery(A2ARemoteAgentDiscovery discovery) {
            this.discovery = discovery;
            return this;
        }

        public Builder serverConfigs(List<A2ARemoteServerConfig> serverConfigs) {
            this.serverConfigs = serverConfigs;
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public SearchRemoteAgentsToolCall build() {
            if (catalog == null) throw new IllegalArgumentException("catalog is required");
            name(TOOL_NAME);
            description("""
                    Search remote server-side agents by capability summary. Remote agent internals, tools, MCP servers,
                    sandbox, and credentials are not exposed. Use this before delegating work to a remote agent.
                    """);
            var parameters = new ArrayList<ToolCallParameter>();
            parameters.add(ToolCallParameter.builder()
                    .name("query")
                    .description("Capability or task keywords to search for. Leave empty to list all available remote agents.")
                    .type(ToolCallParameterType.STRING)
                    .classType(String.class)
                    .required(false)
                    .build());
            parameters(parameters);
            var tool = new SearchRemoteAgentsToolCall();
            super.build(tool);
            tool.catalog = catalog;
            tool.discovery = discovery;
            tool.serverConfigs = serverConfigs != null ? List.copyOf(serverConfigs) : List.of();
            return tool;
        }
    }
}
