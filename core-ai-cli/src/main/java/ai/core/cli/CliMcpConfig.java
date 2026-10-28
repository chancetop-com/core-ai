package ai.core.cli;

import ai.core.bootstrap.PropertiesFileSource;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * @author stephen
 */
final class CliMcpConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(CliMcpConfig.class);

    static void mergeWorkspaceMcpConfig(PropertiesFileSource props, Path workspace) {
        Path mcpFile = resolveWorkspaceMcpFile(workspace);
        if (!Files.exists(mcpFile)) return;
        try {
            String localJson = Files.readString(mcpFile);
            @SuppressWarnings("unchecked")
            var parsedLocal = (Map<String, Object>) JsonUtil.fromJson(Map.class, localJson);
            var localServers = normalizeMcpServers(parsedLocal);
            if (localServers == null || localServers.isEmpty()) return;

            var globalJson = props.property("mcp.servers.json");
            Map<String, Object> merged;
            if (globalJson.isPresent()) {
                @SuppressWarnings("unchecked")
                var parsedGlobal = (Map<String, Object>) JsonUtil.fromJson(Map.class, globalJson.get());
                var globalServers = normalizeMcpServers(parsedGlobal);
                merged = globalServers;
                merged.putAll(localServers);
            } else {
                merged = localServers;
            }
            props.putProperty("mcp.servers.json", JsonUtil.toJson(merged));
            LOGGER.info("merged workspace MCP config from {}: {} server(s)", mcpFile, merged.size());
        } catch (Exception e) {
            LOGGER.warn("failed to merge workspace MCP config from {}: {}", mcpFile, e.getMessage());
        }
    }

    private static Path resolveWorkspaceMcpFile(Path workspace) {
        var coreAiDir = workspace.resolve(".core-ai");
        var canonical = coreAiDir.resolve("MCP.json");
        if (Files.exists(canonical)) return canonical;
        return coreAiDir.resolve("mcp.json");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeMcpServers(Map<String, Object> config) {
        if (config == null || config.isEmpty()) return config;
        var mcpServers = config.get("mcpServers");
        if (mcpServers instanceof Map<?, ?> servers) {
            return (Map<String, Object>) servers;
        }
        return config;
    }

    private CliMcpConfig() {
    }
}
