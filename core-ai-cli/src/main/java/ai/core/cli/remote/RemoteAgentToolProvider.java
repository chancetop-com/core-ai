package ai.core.cli.remote;

import ai.core.cli.auth.AuthConfig;
import ai.core.cli.auth.AuthManager;
import ai.core.tool.ToolCall;
import ai.core.tool.registry.ToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides remote A2A agent tools (search + delegate) as a {@link ToolProvider}.
 *
 * @author Lim Chen
 */
public class RemoteAgentToolProvider implements ToolProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteAgentToolProvider.class);

    public static final String REMOTE_AGENT = "remote-agent";

    public static RemoteAgentToolProvider discover(List<A2ARemoteAgentConfig> remoteAgents,
                                                   List<A2ARemoteServerConfig> remoteServers,
                                                   boolean a2aAutoDiscover) {
        var servers = new ArrayList<>(remoteServers);
        if (servers.isEmpty() && a2aAutoDiscover && AuthManager.isLoggedIn()) {
            var active = AuthConfig.load();
            if (active != null) {
                var auto = new A2ARemoteServerConfig();
                auto.id = "default";
                auto.url = active.serverUrl();
                auto.enabled = true;
                servers.add(auto);
            }
        }
        var catalog = new A2ARemoteAgentDiscovery().discoverCatalog(remoteAgents, servers);
        if (!catalog.isEmpty()) {
            LOGGER.info("Discovered {} remote A2A agent(s) from {} server(s)", catalog.size(), servers.size());
        }
        return new RemoteAgentToolProvider(catalog);
    }

    private final Map<String, ToolCall> tools;

    public RemoteAgentToolProvider(RemoteAgentCatalog catalog) {
        if (catalog.isEmpty()) {
            this.tools = Map.of();
            return;
        }
        var map = new LinkedHashMap<String, ToolCall>();
        var remoteTools = List.of(
                SearchRemoteAgentsToolCall.builder().catalog(catalog).build(),
                DelegateToRemoteAgentToolCall.builder().catalog(catalog).build());
        for (var tc : remoteTools) {
            map.put(tc.getName(), tc);
        }
        this.tools = Map.copyOf(map);
    }

    @Override
    public String id() {
        return REMOTE_AGENT;
    }

    @Override
    public int priority() {
        return 15;
    }

    @Override
    public Map<String, ToolCall> provide() {
        return tools;
    }
}
