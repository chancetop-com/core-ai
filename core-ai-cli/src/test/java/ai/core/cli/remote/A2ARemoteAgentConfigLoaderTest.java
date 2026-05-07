package ai.core.cli.remote;

import ai.core.a2a.A2ARemoteAgentDescriptor;
import ai.core.api.server.agent.AgentDefinitionView;
import ai.core.api.server.agent.ListAgentsResponse;
import ai.core.bootstrap.PropertiesFileSource;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
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

    private A2ARemoteAgentConfig config(String id, String name) {
        var config = new A2ARemoteAgentConfig();
        config.id = id;
        config.url = "https://server";
        config.name = name;
        config.description = "remote agent";
        return config;
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
