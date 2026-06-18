package ai.core.cli;

import ai.core.bootstrap.PropertiesFileSource;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        CliApp.mergeWorkspaceConfig(source, workspace);

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

        CliApp.mergeWorkspaceConfig(source, workspace);

        var servers = servers(source);
        assertEquals(1, servers.size());
        assertEquals("npx", server(servers, "local").get("command"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> servers(PropertiesFileSource source) {
        return (Map<String, Object>) JsonUtil.fromJson(Map.class, source.property("mcp.servers.json").orElseThrow());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> server(Map<String, Object> servers, String name) {
        return (Map<String, Object>) servers.get(name);
    }
}
