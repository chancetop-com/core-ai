package ai.core.cli;

import ai.core.bootstrap.PropertiesFileSource;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliAppTest {
    @TempDir
    Path workspace;

    @Test
    void mergesWorkspaceMcpJsonWrapperAndOverridesGlobalServer() throws Exception {
        var coreAiDir = workspace.resolve(".core-ai");
        Files.createDirectories(coreAiDir);
        Files.writeString(coreAiDir.resolve("MCP.json"), """
            {
              "mcpServers": {
                "global": {"command": "workspace-global"},
                "local": {"url": "http://localhost:9000/mcp"}
              }
            }
            """);
        var props = new Properties();
        props.setProperty("mcp.servers.json", """
            {
              "global": {"command": "global"},
              "kept": {"command": "kept"}
            }
            """);
        var source = new PropertiesFileSource(props);

        CliAppHelper.mergeWorkspaceConfig(source, workspace);

        var servers = servers(source);
        assertEquals(3, servers.size());
        assertEquals("workspace-global", server(servers, "global").get("command"));
        assertEquals("kept", server(servers, "kept").get("command"));
        assertEquals("http://localhost:9000/mcp", server(servers, "local").get("url"));
    }

    @Test
    void mergesLowercaseWorkspaceMcpJson() throws Exception {
        var coreAiDir = workspace.resolve(".core-ai");
        Files.createDirectories(coreAiDir);
        Files.writeString(coreAiDir.resolve("mcp.json"), """
            {
              "local": {"command": "npx", "args": ["-y", "example-mcp"]}
            }
            """);
        var source = new PropertiesFileSource(new Properties());

        CliAppHelper.mergeWorkspaceConfig(source, workspace);

        var servers = servers(source);
        assertEquals(1, servers.size());
        assertEquals("npx", server(servers, "local").get("command"));
    }

    @Test
    void picksSessionFromTheNextPage() {
        var sessions = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(index -> new ai.core.session.SessionPersistence.SessionInfo("session-" + index, Instant.EPOCH))
                .toList();
        var sessionManager = new ai.core.session.SessionManager(new TestSessionPersistence(sessions));
        var output = new StringBuilder();
        var choices = new ArrayDeque<>(List.of("m", "1"));

        var selected = CliAppHelper.pickSession(sessions, sessionManager, output::append, choices::removeFirst);

        assertEquals("session-11", selected);
        assertTrue(output.toString().contains("Recent sessions 11-11 of 11"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> servers(PropertiesFileSource source) {
        return (Map<String, Object>) JsonUtil.fromJson(Map.class, source.property("mcp.servers.json").orElseThrow());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> server(Map<String, Object> servers, String name) {
        return (Map<String, Object>) servers.get(name);
    }

    private static final class TestSessionPersistence implements ai.core.session.SessionPersistence {
        private final List<ai.core.session.SessionPersistence.SessionInfo> sessions;

        private TestSessionPersistence(List<ai.core.session.SessionPersistence.SessionInfo> sessions) {
            this.sessions = sessions;
        }

        @Override
        public void save(String id, String context) {
        }

        @Override
        public void clear() {
        }

        @Override
        public void delete(List<String> ids) {
        }

        @Override
        public java.util.Optional<String> load(String id) {
            return java.util.Optional.empty();
        }

        @Override
        public List<ai.core.session.SessionPersistence.SessionInfo> listSessions() {
            return sessions;
        }
    }
}
