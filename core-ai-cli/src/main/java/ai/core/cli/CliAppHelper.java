package ai.core.cli;

import ai.core.agent.SubAgentConfig;
import ai.core.bootstrap.BootstrapResult;
import ai.core.bootstrap.PropertiesFileSource;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.auth.RuntimeAuthConfig;
import ai.core.cli.media.MediaModelCatalog;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.context.CompressionConfig;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderType;
import ai.core.llm.providers.LiteLLMMediaProvider;
import ai.core.media.CoreAiGatewayMediaProvider;
import ai.core.media.GoogleAccessTokenProvider;
import ai.core.media.MediaProvider;
import ai.core.media.VertexGeminiOmniMediaProvider;
import ai.core.llm.LLMProviders;
import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.session.FileRuleBasedPermissionStore;
import ai.core.session.SessionManager;
import ai.core.session.SessionPersistence.SessionInfo;
import ai.core.tool.tools.AskUserTool;
import ai.core.tool.tools.GlobFileTool;
import ai.core.tool.tools.GrepFileTool;
import ai.core.tool.tools.ReadFileTool;
import ai.core.tool.tools.TaskTool;
import ai.core.tool.tools.WebFetchTool;
import ai.core.tool.tools.WebSearchTool;
import ai.core.tool.tools.WriteTodoTaskTool;
import ai.core.tool.tools.WriteTodosTool;
import ai.core.utils.FfmpegImageShrinker;
import ai.core.utils.ImageDownscaler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * @author stephen
 */
public class CliAppHelper {
    /** Media gateway base derived from the login; chat keeps using {@link RuntimeAuthConfig#LLM_PROXY_PATH}. */
    private static final String MEDIA_GATEWAY_PATH = "/api/gateway/v1";
    private static final String MEDIA_GATEWAY_BASE_PROPERTY = "media.gateway.api.base";
    private static final String MEDIA_GATEWAY_KEY_PROPERTY = "media.gateway.api.key";
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SESSION_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** the CLI proxy rejects a request body over 10MB and the whole history is resent on every turn */
    private static final long CLI_MAX_INLINE_IMAGE_BYTES = 4L * 1024 * 1024;
    private static final String INLINE_IMAGE_MAX_COUNT = "agent.inline.image.max.count";
    private static final String INLINE_IMAGE_MAX_BYTES = "agent.inline.image.max.bytes";

    /**
     * The native CLI has no AWT, so inline images are shrunk by the vendored ffmpeg, and the default
     * inline image budget is tightened to fit the CLI proxy's request body limit. Both limits can be
     * overridden in agent.properties; a configured value also applies to the other providers, while
     * the LiteLLM proxy keeps its 4MB default when nothing is configured.
     */
    public static void configureInlineImages(BootstrapResult result, PropertiesFileSource props) {
        ImageDownscaler.register(new FfmpegImageShrinker());
        applyInlineImageLimits(result, props);
    }

    static void applyInlineImageLimits(BootstrapResult result, PropertiesFileSource props) {
        var maxImages = props.property(INLINE_IMAGE_MAX_COUNT).map(Integer::parseInt).orElse(null);
        var maxBytes = props.property(INLINE_IMAGE_MAX_BYTES).map(CliAppHelper::parseByteSize).orElse(null);
        if (result.liteLLMProvider != null) {
            applyToProvider(result.liteLLMProvider, maxImages, maxBytes != null ? maxBytes : CLI_MAX_INLINE_IMAGE_BYTES);
        }
        boolean hasNoLimits = maxImages == null && maxBytes == null;
        if (result.llmProviders == null || hasNoLimits) return;
        for (var type : result.llmProviders.getProviderTypes()) {
            var provider = result.llmProviders.getProvider(type);
            if (provider == null || provider.equals(result.liteLLMProvider)) continue;
            applyToProvider(provider, maxImages, maxBytes);
        }
    }

    private static void applyToProvider(LLMProvider provider, Integer maxImages, Long maxBytes) {
        if (maxImages != null) provider.config.setMaxInlineImages(maxImages);
        if (maxBytes != null) provider.config.setMaxInlineImageBytes(maxBytes);
    }

    /** Accepts a plain byte count or a KB/MB/GB suffix, e.g. {@code 4194304}, {@code 4MB}, {@code 2m}. */
    static long parseByteSize(String value) {
        var text = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        long multiplier = 1;
        if (text.endsWith("kb") || text.endsWith("mb") || text.endsWith("gb")) {
            multiplier = switch (text.charAt(text.length() - 2)) {
                case 'k' -> 1024L;
                case 'm' -> 1024L * 1024;
                default -> 1024L * 1024 * 1024;
            };
            text = text.substring(0, text.length() - 2);
        } else if (text.endsWith("k") || text.endsWith("m") || text.endsWith("g")) {
            multiplier = switch (text.charAt(text.length() - 1)) {
                case 'k' -> 1024L;
                case 'm' -> 1024L * 1024;
                default -> 1024L * 1024 * 1024;
            };
            text = text.substring(0, text.length() - 1);
        }
        var bytes = Math.multiplyExact(Long.parseLong(text.strip()), multiplier);
        if (bytes == 0) {
            throw new IllegalArgumentException(INLINE_IMAGE_MAX_BYTES + " must not be zero; use -1 for no byte limit");
        }
        return bytes;
    }

    public static CompressionConfig compressionConfig(PropertiesFileSource props) {
        return new CompressionConfig(
                props.property("agent.compression.enabled").map(Boolean::parseBoolean).orElse(null),
                props.property("agent.compression.threshold").map(Double::parseDouble).orElse(null),
                props.property("agent.compression.keep.recent.turns").map(Integer::parseInt).orElse(null),
                props.property("agent.compression.keep.tokens").map(Integer::parseInt).orElse(null),
                props.property("agent.compression.context.window").map(Integer::parseInt).orElse(null),
                props.property("agent.compression.summary.model").map(String::trim).filter(model -> !model.isEmpty()).orElse(null));
    }

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
        CliMcpConfig.mergeWorkspaceMcpConfig(global, workspace);
    }

    /**
     * If {@code litellm.api.base} is not in agent.properties, inject fallback values from saved auth
     * credentials so the server's CLI LLM endpoint is used without manual configuration. The same login
     * also derives the media gateway base: the chat proxy path has no image/video routes, while the
     * gateway owns routing, reference resolution and media job records.
     * <p>
     * An explicit base is respected unless it points at the same CLI proxy the fallback would derive
     * (a common copy-paste of the old derived value). That base still has no media routes, so the media
     * gateway is derived as well.
     */
    public static void injectLiteLLMFallback(PropertiesFileSource props) {
        injectLiteLLMFallback(props, AuthConfig.load());
    }

    static void injectLiteLLMFallback(PropertiesFileSource props, AuthConfig auth) {
        if (auth == null || auth.apiKey() == null) return;
        var serverBase = stripTrailingSlashes(auth.serverUrl());
        var proxyBase = serverBase + RuntimeAuthConfig.LLM_PROXY_PATH;
        var configuredBase = props.property("litellm.api.base").orElse(null);
        if (configuredBase == null) {
            props.putProperty("litellm.api.base", proxyBase);
            props.putProperty("litellm.api.key", auth.apiKey());
        } else {
            if (!stripTrailingSlashes(configuredBase).equals(proxyBase)) return;
            props.putProperty("litellm.api.base", proxyBase);
            if (props.property("litellm.api.key").isEmpty()) props.putProperty("litellm.api.key", auth.apiKey());
        }
        if (props.property(MEDIA_GATEWAY_BASE_PROPERTY).isEmpty()) {
            props.putProperty(MEDIA_GATEWAY_BASE_PROPERTY, serverBase + MEDIA_GATEWAY_PATH);
            props.putProperty(MEDIA_GATEWAY_KEY_PROPERTY, auth.apiKey());
        }
    }

    public static MediaProvider imageMediaProvider(PropertiesFileSource props) {
        var baseUrl = imageMediaBase(props);
        return mediaProvider(baseUrl, mediaKey(props, "media.image.api.key", baseUrl));
    }

    public static MediaProvider videoMediaProvider(PropertiesFileSource props) {
        var protocol = props.property("media.video.protocol").orElseGet(() -> props.property("media.protocol").orElse(null));
        if (!"VERTEX_GEMINI_INTERACTIONS".equalsIgnoreCase(protocol)) {
            var baseUrl = videoMediaBase(props);
            return mediaProvider(baseUrl, mediaKey(props, "media.video.api.key", baseUrl));
        }
        return new VertexGeminiOmniMediaProvider(
                props.property("media.vertex.api.base").orElse(null),
                props.property("media.vertex.project-id").orElse(null),
                props.property("media.vertex.location").orElse("global"),
                new GoogleAccessTokenProvider(googleServiceAccountJson(props)));
    }

    /**
     * The shared endpoint used by both modalities, present only when the user explicitly configured
     * {@code media.api.base}. It is not inferred from a modality-specific provider: that inference made a
     * video-only endpoint look like an image provider and registered generate_image against it.
     */
    public static MediaProvider sharedMediaProvider(PropertiesFileSource props) {
        var baseUrl = props.property("media.api.base").orElse(null);
        if (baseUrl == null || baseUrl.isBlank()) return null;
        return mediaProvider(baseUrl, mediaKey(props, "media.api.key", baseUrl));
    }

    /** The URL a modality would call, or null when the modality has no endpoint configured. */
    public static String imageMediaBase(PropertiesFileSource props) {
        return props.property("media.image.api.base")
                .orElseGet(() -> props.property("media.api.base")
                        .orElseGet(() -> props.property(MEDIA_GATEWAY_BASE_PROPERTY)
                                .orElseGet(() -> props.property("litellm.api.base").orElse(null))));
    }

    public static String videoMediaBase(PropertiesFileSource props) {
        var protocol = props.property("media.video.protocol").orElseGet(() -> props.property("media.protocol").orElse(null));
        if ("VERTEX_GEMINI_INTERACTIONS".equalsIgnoreCase(protocol)) {
            return props.property("media.vertex.api.base").orElse("vertex");
        }
        return props.property("media.video.api.base")
                .orElseGet(() -> props.property("media.api.base")
                        .orElseGet(() -> props.property(MEDIA_GATEWAY_BASE_PROPERTY)
                                .orElseGet(() -> props.property("litellm.api.base").orElse(null))));
    }

    public static String mediaGatewayBase(PropertiesFileSource props) {
        return props.property(MEDIA_GATEWAY_BASE_PROPERTY).map(CliAppHelper::stripTrailingSlashes).orElse(null);
    }

    /**
     * Only a managed gateway can supply a model catalog; a custom endpoint keeps its model in
     * {@code media.*.model}. The fetch is started in the background so engine startup never waits on it.
     */
    public static MediaModelCatalog mediaModelCatalog(MediaProvider imageProvider, MediaProvider videoProvider) {
        if (!(imageProvider instanceof CoreAiGatewayMediaProvider)
                && !(videoProvider instanceof CoreAiGatewayMediaProvider)) {
            return null;
        }
        var catalog = MediaModelCatalog.create(AuthConfig.load());
        if (catalog != null) catalog.warmUp();
        return catalog;
    }

    /** The gateway provider carries the session id; the copy shares the parent's HTTP clients. */
    public static MediaProvider sessionMediaProvider(MediaProvider provider, String sessionId) {
        if (provider instanceof CoreAiGatewayMediaProvider gateway) return gateway.withSessionId(sessionId);
        return provider;
    }

    /**
     * Keys are paired with the endpoint that was chosen: the login-derived gateway key must never be sent
     * to a user-configured third-party endpoint that happens to omit its own key. A custom endpoint keeps
     * the historical {@code litellm.api.key} fallback (its own proxy key).
     */
    private static String mediaKey(PropertiesFileSource props, String specificKey, String baseUrl) {
        var specific = props.property(specificKey).orElse(null);
        if (specific != null) return specific;
        if (isGatewayBase(baseUrl)) {
            return props.property(MEDIA_GATEWAY_KEY_PROPERTY)
                    .orElseGet(() -> props.property("litellm.api.key").orElse(null));
        }
        return props.property("media.api.key")
                .orElseGet(() -> props.property("litellm.api.key").orElse(null));
    }

    private static MediaProvider mediaProvider(String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank()) return null;
        if (isGatewayBase(baseUrl)) return new CoreAiGatewayMediaProvider(baseUrl, apiKey);
        return new LiteLLMMediaProvider(baseUrl, apiKey);
    }

    /**
     * A generic LiteLLM endpoint cannot resolve {@code gateway-media-v1.*} handles (it has no media job
     * index), so only the core-ai gateway is marked as a managed-reference client.
     */
    private static boolean isGatewayBase(String baseUrl) {
        return stripTrailingSlashes(baseUrl).endsWith(MEDIA_GATEWAY_PATH);
    }

    private static String stripTrailingSlashes(String value) {
        if (value == null) return null;
        return value.replaceAll("/+$", "");
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

    public static Map<String, SubAgentConfig> parseSubAgentConfig(PropertiesFileSource props, LLMProviders llmProviders) {
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

    public static FileRuleBasedPermissionStore whiteToolsPermissionStore(Path workspace) {
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
        permissionStore.allow(ReadFileTool.TOOL_NAME);
        permissionStore.allow(GlobFileTool.TOOL_NAME);
        permissionStore.allow(GrepFileTool.TOOL_NAME);
        return permissionStore;
    }
    public static String defaultSessionId(String prefix) {
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
    public static void registerMcpLoadingListener() {
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
    public static void closeShutdownResources(BootstrapResult result) {
        for (var resource : result.shutdownResources()) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // shutdown cleanup failure is non-critical
            }
        }
    }
}
