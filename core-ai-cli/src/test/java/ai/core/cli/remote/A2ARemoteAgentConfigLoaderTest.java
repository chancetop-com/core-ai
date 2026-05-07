package ai.core.cli.remote;

import ai.core.a2a.A2ARemoteAgentDescriptor;
import ai.core.agent.ExecutionContext;
import ai.core.api.server.agent.AgentDefinitionView;
import ai.core.api.server.agent.ListAgentsResponse;
import ai.core.bootstrap.PropertiesFileSource;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2ARemoteAgentConfigLoaderTest {
    @Test
    void loadsRemoteAgentConfig() {
        var props = new Properties();
        props.setProperty("a2a.remoteAgents", "enterprise");
        props.setProperty("a2a.remoteAgents.enterprise.url", "https://server");
        props.setProperty("a2a.remoteAgents.enterprise.agentId", "default-assistant");
        props.setProperty("a2a.remoteAgents.enterprise.apiKey", "test-key");
        props.setProperty("a2a.remoteAgents.enterprise.name", "server default assistant");
        props.setProperty("a2a.remoteAgents.enterprise.description", "server tools");
        props.setProperty("a2a.remoteAgents.enterprise.timeout", "2m");
        props.setProperty("a2a.remoteAgents.enterprise.contextPolicy", "none");
        props.setProperty("a2a.remoteAgents.enterprise.maxInputChars", "100");
        props.setProperty("a2a.remoteAgents.enterprise.maxOutputChars", "200");

        var configs = A2ARemoteAgentConfigLoader.load(new PropertiesFileSource(props));

        assertEquals(1, configs.size());
        var config = configs.getFirst();
        assertEquals("enterprise", config.id);
        assertEquals("https://server", config.url);
        assertEquals("default-assistant", config.agentId);
        assertEquals("test-key", config.resolvedApiKey());
        assertEquals("server_default_assistant", config.name);
        assertEquals("server tools", config.description);
        assertEquals(Duration.ofMinutes(2), config.timeout);
        assertEquals(A2ARemoteAgentDescriptor.ContextPolicy.NONE, config.contextPolicy);
        assertEquals(100, config.maxInputChars);
        assertEquals(200, config.maxOutputChars);
    }

    @Test
    void loadsRemoteServerConfig() {
        var props = new Properties();
        props.setProperty("a2a.remoteServers", "dev");
        props.setProperty("a2a.remoteServers.dev.url", "https://server");
        props.setProperty("a2a.remoteServers.dev.apiKey", "test-key");
        props.setProperty("a2a.remoteServers.dev.discovery.enabled", "true");
        props.setProperty("a2a.remoteServers.dev.discovery.required", "true");
        props.setProperty("a2a.remoteServers.dev.toolPrefix", "server agents");
        props.setProperty("a2a.remoteServers.dev.includeAgents", "default-assistant, coder");
        props.setProperty("a2a.remoteServers.dev.excludeAgents", "draft-agent");
        props.setProperty("a2a.remoteServers.dev.timeout", "30s");
        props.setProperty("a2a.remoteServers.dev.contextPolicy", "none");
        props.setProperty("a2a.remoteServers.dev.invocationMode", "send-sync");
        props.setProperty("a2a.remoteServers.dev.maxInputChars", "100");
        props.setProperty("a2a.remoteServers.dev.maxOutputChars", "200");

        var configs = A2ARemoteAgentConfigLoader.loadServers(new PropertiesFileSource(props));

        assertEquals(1, configs.size());
        var config = configs.getFirst();
        assertEquals("dev", config.id);
        assertEquals("https://server", config.url);
        assertEquals("test-key", config.resolvedApiKey());
        assertTrue(config.discoveryRequired);
        assertEquals("server_agents", config.toolPrefix);
        assertEquals(List.of("default-assistant", "coder"), config.includeAgents);
        assertEquals(List.of("draft-agent"), config.excludeAgents);
        assertEquals(Duration.ofSeconds(30), config.timeout);
        assertEquals(A2ARemoteAgentDescriptor.ContextPolicy.NONE, config.contextPolicy);
        assertEquals(A2ARemoteAgentDescriptor.InvocationMode.SEND_SYNC, config.invocationMode);
        assertEquals(100, config.maxInputChars);
        assertEquals(200, config.maxOutputChars);
    }

    @Test
    void loadsSingleRemoteServerShortcut() {
        var props = new Properties();
        props.setProperty("a2a.remoteServer.url", "https://server");
        props.setProperty("a2a.remoteServer.apiKey", "test-key");

        var configs = A2ARemoteAgentConfigLoader.loadServers(new PropertiesFileSource(props));

        assertEquals(1, configs.size());
        var config = configs.getFirst();
        assertEquals("default", config.id);
        assertEquals("https://server", config.url);
        assertEquals("test-key", config.resolvedApiKey());
    }

    @Test
    void infersRemoteServersFromUrlBlocks() {
        var props = new Properties();
        props.setProperty("a2a.remoteServers.dev.url", "https://dev-server");
        props.setProperty("a2a.remoteServers.prod.url", "https://prod-server");

        var configs = A2ARemoteAgentConfigLoader.loadServers(new PropertiesFileSource(props));

        assertEquals(2, configs.size());
        assertEquals("dev", configs.getFirst().id);
        assertEquals("https://dev-server", configs.getFirst().url);
        assertEquals("prod", configs.get(1).id);
        assertEquals("https://prod-server", configs.get(1).url);
    }

    @Test
    void infersRemoteAgentsFromUrlBlocks() {
        var props = new Properties();
        props.setProperty("a2a.remoteAgents.enterprise.url", "https://server");
        props.setProperty("a2a.remoteAgents.enterprise.agentId", "default-assistant");

        var configs = A2ARemoteAgentConfigLoader.load(new PropertiesFileSource(props));

        assertEquals(1, configs.size());
        assertEquals("enterprise", configs.getFirst().id);
        assertEquals("https://server", configs.getFirst().url);
        assertEquals("default-assistant", configs.getFirst().agentId);
    }

    @Test
    void discoversServerAgentsAsDiscoverableToolConfigs() {
        var server = new A2ARemoteServerConfig();
        server.id = "dev";
        server.url = "https://server";
        server.apiKey = "test-key";
        server.toolPrefix = "server";
        server.excludeAgents = List.of("beta");

        var response = new ListAgentsResponse();
        response.agents = List.of(
                agent("a1", "Alpha Agent", "alpha desc", "AGENT"),
                agent("llm1", "Utility Call", "utility desc", "LLM_CALL"),
                agent("beta", "Beta Agent", "beta desc", "AGENT"));

        var configs = new A2ARemoteAgentDiscovery().fromJson(server, JsonUtil.toJson(response));

        assertEquals(1, configs.size());
        var config = configs.getFirst();
        assertEquals("dev:a1", config.id);
        assertEquals("https://server", config.url);
        assertEquals("a1", config.agentId);
        assertEquals("test-key", config.resolvedApiKey());
        assertEquals("server_Alpha_Agent", config.name);
        assertTrue(config.description.contains("alpha desc"));
        assertTrue(config.discoverable);
        assertTrue(config.autoDiscovered);
    }

    @Test
    void disabledConfigDoesNotRequireUrl() {
        var props = new Properties();
        props.setProperty("a2a.remoteAgents", "enterprise");
        props.setProperty("a2a.remoteAgents.enterprise.enabled", "false");

        var configs = A2ARemoteAgentConfigLoader.load(new PropertiesFileSource(props));

        assertEquals(1, configs.size());
        assertFalse(configs.getFirst().enabled);
    }

    @Test
    void enabledConfigRequiresUrl() {
        var props = new Properties();
        props.setProperty("a2a.remoteAgents", "enterprise");

        assertThrows(IllegalStateException.class,
                () -> A2ARemoteAgentConfigLoader.load(new PropertiesFileSource(props)));
    }

    @Test
    void duplicateRemoteToolNamesAreRejected() {
        var first = config("one", "remote_agent");
        var second = config("two", "remote_agent");

        assertThrows(IllegalStateException.class, () -> A2ARemoteAgentTools.from(List.of(first, second)));
    }

    @Test
    void remoteToolNameConflictingWithReservedToolNameIsRejected() {
        var config = config("one", "read_file");

        assertThrows(IllegalStateException.class, () -> A2ARemoteAgentTools.from(List.of(config), Set.of("read_file")));
    }

    @Test
    void autoDiscoveredRemoteToolNameConflictingWithReservedToolNameIsRenamed() {
        var server = new A2ARemoteServerConfig();
        server.id = "dev";
        var discovered = config("dev:agent", "read_file");
        discovered.agentId = "agent-123456789";
        discovered.autoDiscovered = true;
        discovered.discoverable = true;

        var tools = A2ARemoteAgentTools.from(List.of(), List.of(server), Set.of("read_file"), new A2ARemoteAgentDiscovery() {
            @Override
            public List<A2ARemoteAgentConfig> discover(A2ARemoteServerConfig server) {
                return List.of(discovered);
            }
        });

        assertEquals(1, tools.size());
        assertEquals("read_file_agent-12", tools.getFirst().getName());
        assertTrue(tools.getFirst().isDiscoverable());
    }

    @Test
    void optionalRemoteServerDiscoveryFailureDoesNotBlockStaticTools() {
        var server = new A2ARemoteServerConfig();
        server.id = "dev";
        server.url = "https://server";
        var staticTool = config("static", "static_remote");

        var tools = A2ARemoteAgentTools.from(List.of(staticTool), List.of(server), Set.of(), new A2ARemoteAgentDiscovery() {
            @Override
            public List<A2ARemoteAgentConfig> discover(A2ARemoteServerConfig server) {
                throw new IllegalStateException("server unavailable");
            }
        });

        assertEquals(1, tools.size());
        assertEquals("static_remote", tools.getFirst().getName());
    }

    @Test
    void requiredRemoteServerDiscoveryFailureIsRejected() {
        var server = new A2ARemoteServerConfig();
        server.id = "dev";
        server.url = "https://server";
        server.discoveryRequired = true;

        assertThrows(IllegalStateException.class, () -> A2ARemoteAgentTools.from(List.of(), List.of(server), Set.of(),
                new A2ARemoteAgentDiscovery() {
                    @Override
                    public List<A2ARemoteAgentConfig> discover(A2ARemoteServerConfig server) {
                        throw new IllegalStateException("server unavailable");
                    }
                }));
    }

    @Test
    void discoveryUsesShortCacheAndReturnsIndependentConfigs() {
        A2ARemoteAgentDiscovery.clearCacheForTesting();
        var server = new A2ARemoteServerConfig();
        server.id = "cache-test";
        server.url = "https://server";
        server.apiKey = "test-key";
        server.toolPrefix = "server";

        class CountingDiscovery extends A2ARemoteAgentDiscovery {
            int calls;

            @Override
            protected String fetchAgentsJson(A2ARemoteServerConfig server, String apiKey) {
                calls++;
                var response = new ListAgentsResponse();
                response.agents = List.of(agent("a1", "Alpha Agent", "alpha desc", "AGENT"));
                return JsonUtil.toJson(response);
            }
        }

        var discovery = new CountingDiscovery();
        var first = discovery.discover(server);
        first.getFirst().name = "mutated";
        var second = discovery.discover(server);

        assertEquals(1, discovery.calls);
        assertEquals("server_Alpha_Agent", second.getFirst().name);
        A2ARemoteAgentDiscovery.clearCacheForTesting();
    }

    @Test
    void discoversCatalogEntriesAndDeduplicatesSameRemoteAgent() {
        var staticConfig = config("manual", "manual_alpha");
        staticConfig.agentId = "a1";
        staticConfig.displayName = "Manual Alpha";
        var server = new A2ARemoteServerConfig();
        server.id = "dev";
        server.url = "https://server";

        var discovered = config("dev:a1", "dev_alpha");
        discovered.agentId = "a1";
        discovered.displayName = "Discovered Alpha";
        discovered.serverId = "dev";
        discovered.autoDiscovered = true;

        var catalog = new A2ARemoteAgentDiscovery() {
            @Override
            public List<A2ARemoteAgentConfig> discover(A2ARemoteServerConfig server) {
                return List.of(discovered);
            }
        }.discoverCatalog(List.of(staticConfig), List.of(server));

        assertEquals(1, catalog.size());
        assertEquals("manual", catalog.entries().getFirst().id());
        assertEquals("Manual Alpha", catalog.entries().getFirst().name());
    }

    @Test
    void searchRemoteAgentsReturnsCatalogSummary() {
        var catalog = new RemoteAgentCatalog(List.of(entry("dev:jira", "dev", "jira",
                "Jira Agent", "Handles Jira issue lookup and workflow updates.")));
        var tool = SearchRemoteAgentsToolCall.builder().catalog(catalog).build();

        var result = tool.execute(JsonUtil.toJson(Map.of("query", "jira workflow")));

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("Found 1 remote agents"));
        assertTrue(result.getResult().contains("dev:jira"));
        assertTrue(result.getResult().contains("Handles Jira issue lookup"));
    }

    @Test
    void delegateToRemoteAgentUsesCatalogEntryAndTask() {
        var catalog = new RemoteAgentCatalog(List.of(entry("dev:jira", "dev", "jira",
                "Jira Agent", "Handles Jira issue lookup and workflow updates.")));
        var tool = DelegateToRemoteAgentToolCall.builder().catalog(catalog).delegateFactory(config -> new ToolCall() {
            @Override
            public ToolCallResult execute(String arguments, ExecutionContext context) {
                var args = JsonUtil.toMap(arguments);
                assertEquals("Summarize CORE-123", args.get("query"));
                return ToolCallResult.completed("delegated result");
            }

            @Override
            public ToolCallResult execute(String arguments) {
                return execute(arguments, null);
            }
        }).build();

        var result = tool.execute(JsonUtil.toJson(Map.of("agent_id", "dev:jira", "task", "Summarize CORE-123")));

        assertTrue(result.isCompleted());
        assertEquals("delegated result", result.getResult());
        assertEquals("dev:jira", result.getStats().get("remote_catalog_agent_id"));
        assertEquals("Jira Agent", result.getStats().get("remote_agent_name"));
    }

    private A2ARemoteAgentConfig config(String id, String name) {
        var config = new A2ARemoteAgentConfig();
        config.id = id;
        config.url = "https://server";
        config.name = name;
        config.description = "remote agent";
        return config;
    }

    private RemoteAgentCatalogEntry entry(String id, String serverId, String agentId, String name, String description) {
        var config = config(id, name);
        config.serverId = serverId;
        config.agentId = agentId;
        config.displayName = name;
        config.description = description;
        return new RemoteAgentCatalogEntry(id, serverId, agentId, name, description, "PUBLISHED", config);
    }

    private AgentDefinitionView agent(String id, String name, String description, String type) {
        var agent = new AgentDefinitionView();
        agent.id = id;
        agent.name = name;
        agent.description = description;
        agent.type = type;
        agent.status = "PUBLISHED";
        return agent;
    }
}
