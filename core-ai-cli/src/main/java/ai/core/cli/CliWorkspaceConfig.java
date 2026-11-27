package ai.core.cli;

import ai.core.bootstrap.PropertiesFileSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Workspace-local configuration merged on top of the global properties.
 *
 * <p>The workspace {@code MCP.json} is skipped in desktop-managed MCP mode: the desktop store is
 * the only MCP source there, so the file must not be loaded.
 *
 * @author stephen
 */
public final class CliWorkspaceConfig {
    public static void merge(PropertiesFileSource global, Path workspace, boolean includeWorkspaceMcp) {
        Path localConfig = workspace.resolve(".core-ai").resolve("agent.properties");
        if (Files.exists(localConfig)) {
            try (var is = Files.newInputStream(localConfig)) {
                var localProps = new Properties();
                localProps.load(is);
                localProps.forEach((k, v) -> global.putProperty((String) k, (String) v));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to load workspace-local config: " + localConfig, e);
            }
        }

        if (!includeWorkspaceMcp) {
            global.removeProperty("mcp.servers.json");
            return;
        }
        // Merge workspace MCP.json into mcp.servers.json so both
        // STDIO and HTTP MCP servers are auto-loaded on startup.
        CliMcpConfig.mergeWorkspaceMcpConfig(global, workspace);
    }

    private CliWorkspaceConfig() {
    }
}
