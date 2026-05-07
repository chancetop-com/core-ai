package ai.core.cli.remote;

import ai.core.a2a.A2ARemoteAgentDescriptor;
import ai.core.bootstrap.PropertiesFileSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private A2ARemoteAgentConfig config(String id, String name) {
        var config = new A2ARemoteAgentConfig();
        config.id = id;
        config.url = "https://server";
        config.name = name;
        config.description = "remote agent";
        return config;
    }
}
