package ai.core.cli;

import ai.core.agent.SubAgentConfig;
import ai.core.bootstrap.BootstrapResult;
import ai.core.bootstrap.PropertiesFileSource;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderType;
import ai.core.llm.providers.LiteLLMMediaProvider;
import ai.core.media.GoogleAccessTokenProvider;
import ai.core.media.MediaProvider;
import ai.core.media.VertexGeminiOmniMediaProvider;
import ai.core.llm.LLMProviders;
import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.session.FileRuleBasedPermissionStore;
import ai.core.session.SessionManager;
import ai.core.session.SessionPersistence.SessionInfo;
import ai.core.session.ToolPermissionStore;
import ai.core.tool.tools.AskUserTool;
import ai.core.tool.tools.GlobFileTool;
import ai.core.tool.tools.GrepFileTool;
import ai.core.tool.tools.MemoryTool;
import ai.core.tool.tools.ReadFileTool;
import ai.core.tool.tools.TaskTool;
import ai.core.tool.tools.WebFetchTool;
import ai.core.tool.tools.WebSearchTool;
import ai.core.tool.tools.WriteTodoTaskTool;
import ai.core.tool.tools.WriteTodosTool;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * @author stephen
 */
public class CliAppHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(CliAppHelper.class);
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SESSION_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static void mergeWorkspaceConfig(PropertiesFileSource global, Path workspace) {
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

        // Merge workspace MCP.json into mcp.servers.json so both
        // STDIO and HTTP MCP servers are auto-loaded on startup.
        mergeWorkspaceMcpConfig(global, workspace);
    }

    private static void mergeWorkspaceMcpConfig(PropertiesFileSource props, Path workspace) {
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

    /**
     * If {@code litellm.api.base} is not in agent.properties, inject fallback
     * values from saved auth credentials so the server's LiteLLM proxy is used
     * without manual configuration.
     */
    static void injectLiteLLMFallback(PropertiesFileSource props) {
        if (props.property("litellm.api.base").isPresent()) return;
        var auth = AuthConfig.load();
        if (auth != null && auth.apiKey() != null) {
            props.putProperty("litellm.api.base", auth.serverUrl() + "/api/litellm/v1");
            props.putProperty("litellm.api.key", auth.apiKey());
        }
    }

    public static MediaProvider imageMediaProvider(PropertiesFileSource props) {
        var baseUrl = props.property("media.image.api.base")
                .orElseGet(() -> props.property("media.api.base").orElseGet(() -> props.property("litellm.api.base").orElse(null)));
        var apiKey = props.property("media.image.api.key")
                .orElseGet(() -> props.property("media.api.key").orElseGet(() -> props.property("litellm.api.key").orElse(null)));
        if (baseUrl == null || baseUrl.isBlank()) return null;
        return new LiteLLMMediaProvider(baseUrl, apiKey);
    }

    public static MediaProvider videoMediaProvider(PropertiesFileSource props) {
        var protocol = props.property("media.video.protocol").orElseGet(() -> props.property("media.protocol").orElse(null));
        if (!"VERTEX_GEMINI_INTERACTIONS".equalsIgnoreCase(protocol)) {
            var baseUrl = props.property("media.video.api.base").orElseGet(() -> props.property("media.api.base")
                    .orElseGet(() -> props.property("litellm.api.base").orElse(null)));
            var apiKey = props.property("media.video.api.key").orElseGet(() -> props.property("media.api.key")
                    .orElseGet(() -> props.property("litellm.api.key").orElse(null)));
            return baseUrl == null || baseUrl.isBlank() ? null : new LiteLLMMediaProvider(baseUrl, apiKey);
        }
        return new VertexGeminiOmniMediaProvider(
                props.property("media.vertex.api.base").orElse(null),
                props.property("media.vertex.project-id").orElse(null),
                props.property("media.vertex.location").orElse("global"),
                new GoogleAccessTokenProvider(googleServiceAccountJson(props)));
    }

    public static MediaProvider mediaProvider(PropertiesFileSource props) {
        var imageProvider = imageMediaProvider(props);
        return imageProvider != null ? imageProvider : videoMediaProvider(props);
    }

    private static String googleServiceAccountJson(PropertiesFileSource props) {
        var inlineJson = props.property("media.video.google.service-account-json")
                .orElseGet(() -> props.property("media.google.service-account-json").orElse(null));
        if (inlineJson != null && !inlineJson.isBlank()) return inlineJson;
        var serviceAccountFile = props.property("media.video.google.service-account-file")
                .orElseGet(() -> props.property("media.google.service-account-file").orElse(null));
        if (serviceAccountFile == null || serviceAccountFile.isBlank()) return null;
        try {
            return Files.readString(Path.of(serviceAccountFile));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read Google service account file: " + serviceAccountFile, e);
        }
    }

    static Map<String, SubAgentConfig> parseSubAgentConfig(PropertiesFileSource props, LLMProviders llmProviders) {
        Map<String, SubAgentConfig> configs = new HashMap<>();
        String prefix = "agent.sub.";
        for (String key : props.propertyNames()) {
            if (!key.startsWith(prefix)) continue;
            String suffix = key.substring(prefix.length());
            String agentName;
            if (suffix.endsWith(".model")) {
                agentName = suffix.substring(0, suffix.length() - ".model".length());
                props.property(key).ifPresent(model -> configs.computeIfAbsent(agentName, k -> new SubAgentConfig()).model(model));
            } else if (suffix.endsWith(".provider")) {
                agentName = suffix.substring(0, suffix.length() - ".provider".length());
                props.property(key).ifPresent(providerName -> {
                    var provider = resolveProvider(providerName, llmProviders);
                    if (provider != null) {
                        configs.computeIfAbsent(agentName, k -> new SubAgentConfig()).llmProvider(provider);
                    }
                });
            } else if (suffix.endsWith(".max-turn")) {
                agentName = suffix.substring(0, suffix.length() - ".max-turn".length());
                props.property(key).map(Integer::parseInt).ifPresent(maxTurn ->
                        configs.computeIfAbsent(agentName, k -> new SubAgentConfig()).maxTurnNumber(maxTurn));
            }
        }
        return configs;
    }

    private static LLMProvider resolveProvider(String name, LLMProviders llmProviders) {
        try {
            var type = LLMProviderType.fromName(name);
            return llmProviders.getProvider(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static ToolPermissionStore whiteToolsPermissionStore(Path workspace) {
        var permissionStore = new FileRuleBasedPermissionStore(workspace.resolve(".core-ai").resolve("tool-permissions.json"));
        permissionStore.allow(WriteTodosTool.WT_TOOL_NAME);
        permissionStore.allow(WriteTodoTaskTool.TOOL_NAME_CREATE);
        permissionStore.allow(WriteTodoTaskTool.TOOL_NAME_UPDATE);
        permissionStore.allow(WriteTodoTaskTool.TOOL_NAME_LIST);
        permissionStore.allow(WriteTodoTaskTool.TOOL_NAME_GET);
        permissionStore.allow(TaskTool.TOOL_NAME);
        permissionStore.allow(WebFetchTool.TOOL_NAME);
        permissionStore.allow(WebSearchTool.TOOL_NAME);
        permissionStore.allow(AskUserTool.TOOL_NAME);
        permissionStore.allow(MemoryTool.TOOL_NAME);
        permissionStore.allow(ReadFileTool.TOOL_NAME);
        permissionStore.allow(GlobFileTool.TOOL_NAME);
        permissionStore.allow(GrepFileTool.TOOL_NAME);
        return permissionStore;
    }
    static String defaultSessionId(String prefix) {
        return prefix + LocalDateTime.now().format(SESSION_ID_FORMAT);
    }
    static String pickSession(List<SessionInfo> sessions, SessionManager sessionManager,
                              Consumer<String> output, Supplier<String> input) {
        int pageStart = 0;
        while (true) {
            int pageEnd = Math.min(pageStart + 10, sessions.size());
            output.accept("\nRecent sessions " + (pageStart + 1) + "-" + pageEnd + " of " + sessions.size() + ":\n\n");
            for (int i = pageStart; i < pageEnd; i++) {
                var session = sessions.get(i);
                String timeStr = LocalDateTime.ofInstant(session.lastModified(), ZoneId.systemDefault()).format(DISPLAY_FORMAT);
                String title = truncate(sessionManager.firstUserMessage(session.id()), 50);
                output.accept(String.format("  %2d) %s (%s)%n", i - pageStart + 1, title, timeStr));
            }
            output.accept("\n");
            var prompt = new StringBuilder(80).append("Select session (1-").append(pageEnd - pageStart).append("), 'n' for new");
            if (pageEnd < sessions.size()) prompt.append(", 'm' for more");
            if (pageStart > 0) prompt.append(", 'p' for previous");
            output.accept(prompt.append(": ").toString());
            var choice = input.get();
            if (choice == null || "n".equalsIgnoreCase(choice.trim())) return null;
            if (pageEnd < sessions.size() && "m".equalsIgnoreCase(choice.trim())) {
                pageStart = pageEnd;
                continue;
            }
            if (pageStart > 0 && "p".equalsIgnoreCase(choice.trim())) {
                pageStart = Math.max(0, pageStart - 10);
                continue;
            }
            try {
                int idx = Integer.parseInt(choice.trim());
                if (idx >= 1 && idx <= pageEnd - pageStart) return sessions.get(pageStart + idx - 1).id();
            } catch (NumberFormatException ignored) {
                // fall through to re-prompt
            }
            output.accept("Invalid selection.\n");
        }
    }
    private static String truncate(String text, int maxLength) {
        if (text == null || text.isBlank()) return "(empty)";
        String cleaned = text.replaceAll("[\\r\\n]+", " ").strip();
        if (cleaned.length() <= maxLength) return cleaned;
        return cleaned.substring(0, maxLength) + "...";
    }

    static void closeQuietly(TerminalUI ui) {
        try {
            ui.close();
        } catch (Exception ignored) {
            // terminal cleanup failure is non-critical
        }
    }
    static void registerMcpLoadingListener() {
        McpClientManagerRegistry.addCreationListener(manager ->
                manager.addListener((serverName, oldState, newState) -> {
                    if (newState == McpClientManager.ConnectionState.CONNECTING) {
                        ConsoleWriter.clearLineAndPrint(AnsiTheme.MUTED + "  Loading MCP server: " + serverName + "..." + AnsiTheme.RESET);
                    } else if (newState == McpClientManager.ConnectionState.CONNECTED) {
                        ConsoleWriter.clearLineAndPrint(AnsiTheme.MUTED + "  MCP server loaded: " + serverName + AnsiTheme.RESET);
                    } else if (newState == McpClientManager.ConnectionState.FAILED) {
                        ConsoleWriter.clearLineAndPrint(AnsiTheme.WARNING + "  MCP server failed: " + serverName + AnsiTheme.RESET);
                    }
                })
        );
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("DE_MIGHT_IGNORE")
    static void closeShutdownResources(BootstrapResult result) {
        for (var resource : result.shutdownResources()) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // shutdown cleanup failure is non-critical
            }
        }
    }
}
