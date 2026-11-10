package ai.core.cli.tool;

import ai.core.agent.ExecutionContext;
import ai.core.bootstrap.LLMProviderFactory;
import ai.core.bootstrap.PropertiesFileSource;
import ai.core.cli.CliAppHelper;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.decision.DecisionSupport;
import ai.core.cli.hub.HubCredentialResolver;
import ai.core.cli.media.MediaModelCatalog;
import ai.core.cli.utils.PathUtils;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.llm.providers.LiteLLMProvider;
import ai.core.media.CoreAiGatewayMediaProvider;
import ai.core.media.MediaProvider;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import ai.core.tool.tools.CaptionImageTool;
import ai.core.tool.tools.DecideTool;
import ai.core.tool.tools.GenerateImageTool;
import ai.core.tool.tools.GenerateVideoTool;
import ai.core.tool.tools.GetVideoStatusTool;
import ai.core.tool.tools.MediaModelHint;
import ai.core.tool.tools.SummarizePdfTool;
import ai.core.tool.tools.UnderstandVideoTool;
import ai.core.utils.JsonUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The builtin tools a local session can call: the same media and multimodal tools an agent turn has, assembled
 * from the user's own CLI configuration so the script and the agent see one set of models and providers.
 *
 * @author stephen
 */
public final class LocalToolRuntime {
    public static final String KIND_BUILTIN = "builtin";
    public static final int MAX_TIMEOUT_SECONDS = 1800;
    private static final int MEDIA_CATALOG_WAIT_MS = 3000;

    public static PropertiesFileSource properties(Path workspace) {
        return properties(workspace, null);
    }

    public static PropertiesFileSource properties(Path workspace, Path configFile) {
        var file = configFile != null ? configFile : PathUtils.DEFAULT_CONFIG;
        if (configFile != null && !Files.exists(configFile)) {
            throw new IllegalArgumentException("config file not found: " + configFile);
        }
        PropertiesFileSource props;
        if (Files.exists(file)) {
            props = PropertiesFileSource.fromFile(file);
        } else {
            props = new PropertiesFileSource(new Properties());
        }
        CliAppHelper.mergeWorkspaceConfig(props, workspace);
        return props;
    }

    public static AuthConfig auth(HubCredentialResolver.HubCredentials credentials) {
        var saved = AuthConfig.load();
        if (credentials == null) return saved;
        var matching = AuthConfig.load(credentials.serverUrl());
        var userId = matching != null ? matching.userId() : null;
        return new AuthConfig(credentials.serverUrl(), credentials.apiKey(), userId, null, null, null, true,
                credentials.insecure());
    }

    public static LocalToolRuntime load(Path workspace, HubCredentialResolver.HubCredentials credentials) {
        return load(workspace, credentials, null);
    }

    public static LocalToolRuntime load(Path workspace, HubCredentialResolver.HubCredentials credentials, Path configFile) {
        var auth = auth(credentials);
        return load(workspace, credentials, auth, properties(workspace, configFile));
    }

    static LocalToolRuntime load(Path workspace, HubCredentialResolver.HubCredentials credentials, AuthConfig auth,
                                 PropertiesFileSource props) {
        CliAppHelper.injectLiteLLMFallback(props, auth);
        var imageProvider = CliAppHelper.imageMediaProvider(props);
        var videoProvider = CliAppHelper.videoMediaProvider(props);
        var mediaProvider = CliAppHelper.sharedMediaProvider(props);
        var mediaCatalog = CliAppHelper.mediaModelCatalog(imageProvider, videoProvider, auth);
        if (mediaCatalog != null) mediaCatalog.ensureLoaded(MEDIA_CATALOG_WAIT_MS);
        var llmProviders = LLMProviderFactory.create(props);
        applyActiveProvider(props, llmProviders);
        var llmProvider = llmProviders.getProviderTypes().isEmpty() ? null : llmProviders.getDefaultProvider();
        return new LocalToolRuntime(workspace, credentials, auth, props,
                new Providers(mediaProvider, imageProvider, videoProvider, mediaCatalog, llmProvider));
    }

    private static void applyActiveProvider(PropertiesFileSource props, LLMProviders providers) {
        props.property("active.provider").ifPresent(name -> {
            var type = LLMProviderType.fromName(name);
            if (type != null && providers.getProvider(type) != null) providers.setDefaultProvider(type);
        });
    }

    /**
     * The task-poll path skips the media model catalog: polling a submitted video only needs the provider,
     * and the SDK calls it every second.
     */
    public static ToolCallResult pollVideoTask(Path workspace, HubCredentialResolver.HubCredentials credentials,
                                               Path configFile, String videoId) {
        var props = properties(workspace, configFile);
        CliAppHelper.injectLiteLLMFallback(props, auth(credentials));
        var provider = CliAppHelper.videoMediaProvider(props);
        if (provider == null) provider = CliAppHelper.sharedMediaProvider(props);
        if (provider == null) return ToolCallResult.failed("video generation is not configured for this CLI");
        var result = GenerateVideoTool.builder(provider).build().poll(videoId);
        if (!result.isPending()) new LocalVideoTaskStore(workspace).clear(videoId);
        return result;
    }

    private final Path workspace;
    private final HubCredentialResolver.HubCredentials credentials;
    private final AuthConfig auth;
    private final MediaProvider mediaProvider;
    private final MediaProvider imageMediaProvider;
    private final MediaProvider videoMediaProvider;
    private final MediaModelCatalog mediaCatalog;
    private final LLMProvider llmProvider;
    private final LLMProvider gatewayLlmProvider;
    private final DecisionSupport decisionSupport;
    private final String imageModel;
    private final String imageEditModel;
    private final String videoModel;
    private final String captionModel;
    private final String summarizePdfModel;
    private final List<MediaModelHint> imageModels;
    private final List<MediaModelHint> imageEditModels;
    private final List<MediaModelHint> videoModels;
    private final Map<String, LocalBuiltinTool> tools;
    private final LocalVideoTaskStore videoTasks;

    private LocalToolRuntime(Path workspace, HubCredentialResolver.HubCredentials credentials, AuthConfig auth,
                             PropertiesFileSource props, Providers providers) {
        this.workspace = workspace;
        this.credentials = credentials;
        this.auth = auth;
        this.mediaProvider = providers.media();
        this.imageMediaProvider = providers.image();
        this.videoMediaProvider = providers.video();
        this.mediaCatalog = providers.catalog();
        this.llmProvider = providers.llm();
        this.decisionSupport = DecisionSupport.create(auth);
        this.gatewayLlmProvider = gatewayLlmProvider(props, providers.llm());
        var imageProvider = providers.image() != null ? providers.image() : providers.media();
        var videoProvider = providers.video() != null ? providers.video() : providers.media();
        this.imageModels = mediaCatalog == null ? List.of() : mediaCatalog.imageModels();
        this.imageEditModels = mediaCatalog == null ? List.of() : mediaCatalog.imageEditModels();
        this.videoModels = mediaCatalog == null ? List.of() : mediaCatalog.videoModels();
        this.imageModel = effectiveImageModel(imageProvider, props.property("media.image.model").orElse(null), mediaCatalog);
        this.videoModel = effectiveVideoModel(videoProvider, props.property("media.video.model").orElse(null), mediaCatalog);
        this.imageEditModel = effectiveImageEditModel(imageProvider, props.property("media.image.model").orElse(null),
                props.property("media.image.edit.model").orElse(null), mediaCatalog);
        this.captionModel = props.property("media.caption.model").orElse(null);
        this.summarizePdfModel = props.property("media.summarize_pdf.model").orElse(null);
        this.videoTasks = new LocalVideoTaskStore(workspace);
        this.tools = buildTools();
    }

    /**
     * The gateway responses route, which is what reads a PDF attachment. The CLI chat proxy only serves
     * chat.completions, so summarize_pdf talks to the gateway directly with the same user key.
     */
    private LLMProvider gatewayLlmProvider(PropertiesFileSource props, LLMProvider llmProvider) {
        var base = CliAppHelper.mediaGatewayBase(props);
        var key = props.property("media.gateway.api.key").orElseGet(() -> props.property("litellm.api.key").orElse(null));
        if (base == null || key == null || llmProvider == null) return null;
        return new LiteLLMProvider(new LLMProviderConfig(llmProvider.config), base, key);
    }

    private Map<String, LocalBuiltinTool> buildTools() {
        var builtins = new LinkedHashMap<String, LocalBuiltinTool>();
        var imageProvider = imageMediaProvider != null ? imageMediaProvider : mediaProvider;
        var videoProvider = videoMediaProvider != null ? videoMediaProvider : mediaProvider;
        if (toolAvailable(imageProvider, imageModel)) {
            var managed = imageProvider instanceof CoreAiGatewayMediaProvider;
            var description = GenerateImageTool.buildDescription(managed ? imageModels : List.of(),
                    managed ? imageEditModels : List.of());
            if (imageModel != null) description += "\n\nDefault image model for this session: " + imageModel;
            add(builtins, GenerateImageTool.builder().description(description).build());
        }
        if (toolAvailable(videoProvider, videoModel)) {
            var managed = videoProvider instanceof CoreAiGatewayMediaProvider;
            var description = GenerateVideoTool.buildDescription(managed ? videoModels : List.of());
            if (videoModel != null) description += "\n\nDefault video model for this session: " + videoModel;
            add(builtins, GenerateVideoTool.builder(videoProvider).description(description).build());
            add(builtins, GetVideoStatusTool.builder().build());
        }
        if (llmProvider != null) {
            add(builtins, CaptionImageTool.builder().build());
            add(builtins, SummarizePdfTool.builder().build());
        }
        if (decisionSupport.available()) {
            var description = DecideTool.buildDescription(decisionSupport.models());
            var defaultModel = decisionSupport.defaultModel();
            if (defaultModel != null) description += "\n\nDefault decision model for this session: " + defaultModel;
            add(builtins, DecideTool.builder().description(description).build());
        }
        if (credentials != null) {
            var tool = UnderstandVideoTool.builder(new HubVideoUnderstandingService(credentials)).build();
            tool.setDescription(tool.getDescription()
                    + "\nOutside a sandbox attachment_reference_id may also be a local video file path or an http(s) URL.");
            add(builtins, tool);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(builtins));
    }

    private void add(Map<String, LocalBuiltinTool> builtins, ToolCall tool) {
        var seconds = (int) Math.min(MAX_TIMEOUT_SECONDS, (tool.getTimeoutMs() + 999) / 1000);
        builtins.put(tool.getName(), new LocalBuiltinTool(tool.getName(), tool, seconds));
    }

    public List<LocalBuiltinTool> tools() {
        return List.copyOf(new LinkedHashMap<>(tools).values());
    }

    public Optional<LocalBuiltinTool> tool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public ExecutionContext context(String sessionId) {
        return context(sessionId, null);
    }

    public ExecutionContext context(String sessionId, String toolName) {
        var variables = new LinkedHashMap<String, Object>();
        put(variables, "workspace", workspace.toAbsolutePath().toString());
        put(variables, "media.image.model", imageModel);
        put(variables, "media.image.default.model", imageModel);
        put(variables, "media.image.edit.model", imageEditModel);
        if (!imageEditModels.isEmpty()) variables.put("media.image.edit.models", modelIds(imageEditModels));
        put(variables, "media.video.model", videoModel);
        put(variables, "media.video.default.model", videoModel);
        put(variables, "media.caption.model", captionModel);
        put(variables, "media.summarize_pdf.model", summarizePdfModel);
        var sink = new LocalMediaSink(workspace);
        var context = ExecutionContext.builder()
                .sessionId(sessionId)
                .userId(auth == null ? null : auth.userId())
                .customVariables(variables)
                .customVariable(GenerateImageTool.IMAGE_OUTPUT_SINK_CONTEXT_KEY, sink)
                .customVariable(GetVideoStatusTool.VIDEO_OUTPUT_SINK_CONTEXT_KEY, sink)
                .build();
        var provider = gatewayLlmProvider != null && "summarize_pdf".equals(toolName) ? gatewayLlmProvider : llmProvider;
        context.setLlmProvider(provider);
        context.setModel(provider == null ? null : provider.config.getModel());
        context.setMultiModalModel(provider == null ? null : provider.config.getMultiModalModel());
        context.setMediaProvider(mediaProvider);
        context.setImageMediaProvider(CliAppHelper.sessionMediaProvider(imageMediaProvider != null ? imageMediaProvider : mediaProvider, sessionId));
        context.setVideoMediaProvider(CliAppHelper.sessionMediaProvider(videoMediaProvider != null ? videoMediaProvider : mediaProvider, sessionId));
        context.setDecisionProvider(decisionSupport.provider());
        return context;
    }

    public ToolCallResult execute(LocalBuiltinTool builtin, String arguments, int timeoutSeconds, ExecutionContext context) {
        var tool = builtin.tool();
        Map<String, Object> args;
        try {
            args = tool.parseArguments(arguments);
        } catch (RuntimeException e) {
            return ToolCallResult.failed("arguments are not valid JSON: " + e.getMessage(), e);
        }
        tool.applyContextArguments(args, context);
        var missing = tool.normalizeArguments(args);
        if (!missing.isEmpty()) {
            return ToolCallResult.failed("missing required parameters: " + String.join(", ", missing));
        }
        if (GenerateVideoTool.TOOL_NAME.equals(builtin.name())) {
            var pending = videoTasks.pending();
            if (pending != null && pollVideo(pending).isPending()) {
                return ToolCallResult.failed("There is already a video generation task in progress: video_id=" + pending
                        + ". Poll it with get_video_status until it reports 'completed' or 'failed' before submitting "
                        + "a new generate_video request.");
            }
        }
        var payload = JsonUtil.toJson(args);
        var future = CompletableFuture.supplyAsync(() -> tool.execute(payload, context));
        try {
            var result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            recordVideoTask(builtin, result);
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            return ToolCallResult.failed("tool call timed out after " + timeoutSeconds + "s: " + tool.getName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ToolCallResult.failed("tool call interrupted: " + tool.getName());
        } catch (ExecutionException e) {
            var cause = e.getCause();
            return ToolCallResult.failed(cause == null ? e.getMessage() : cause.getMessage(), cause);
        }
    }

    private void recordVideoTask(LocalBuiltinTool builtin, ToolCallResult result) {
        if (GenerateVideoTool.TOOL_NAME.equals(builtin.name()) && result.isPending() && result.getTaskId() != null) {
            videoTasks.pending(result.getTaskId());
        }
        if (GetVideoStatusTool.TOOL_NAME.equals(builtin.name())) {
            var videoId = stringStat(result, "video_id");
            var status = stringStat(result, "status");
            if (videoId != null && ("completed".equals(status) || "failed".equals(status))) {
                videoTasks.clear(videoId);
            }
        }
    }

    private String stringStat(ToolCallResult result, String key) {
        var stats = result.getStats();
        if (stats == null) return null;
        var value = stats.get(key);
        return value instanceof String text ? text : null;
    }

    public ToolCallResult pollVideo(String videoId) {
        var builtin = tools.get(GenerateVideoTool.TOOL_NAME);
        if (builtin == null || !(builtin.tool() instanceof GenerateVideoTool tool)) {
            return ToolCallResult.failed("video generation is not configured for this CLI");
        }
        var result = tool.poll(videoId);
        if (!result.isPending()) videoTasks.clear(videoId);
        return result;
    }

    public boolean hasVideo() {
        return tools.containsKey(GenerateVideoTool.TOOL_NAME);
    }

    private boolean toolAvailable(MediaProvider provider, String model) {
        if (provider == null) return false;
        return !(provider instanceof CoreAiGatewayMediaProvider) || model != null;
    }

    private String effectiveImageModel(MediaProvider provider, String configured, MediaModelCatalog catalog) {
        if (configured != null && !configured.isBlank()) return configured.trim();
        if (!(provider instanceof CoreAiGatewayMediaProvider) || catalog == null) return null;
        return catalog.defaultImageModel();
    }

    private String effectiveVideoModel(MediaProvider provider, String configured, MediaModelCatalog catalog) {
        if (configured != null && !configured.isBlank()) return configured.trim();
        if (!(provider instanceof CoreAiGatewayMediaProvider) || catalog == null) return null;
        return catalog.defaultVideoModel();
    }

    private String effectiveImageEditModel(MediaProvider provider, String configuredImage, String configuredEdit,
                                           MediaModelCatalog catalog) {
        if (configuredEdit != null && !configuredEdit.isBlank()) return configuredEdit.trim();
        if (!(provider instanceof CoreAiGatewayMediaProvider)) return effectiveImageModel(provider, configuredImage, null);
        var imageModel = effectiveImageModel(provider, configuredImage, catalog);
        var editIds = modelIds(imageEditModels);
        if (imageModel != null && editIds.contains(imageModel)) return imageModel;
        var catalogEditModel = catalog == null ? null : catalog.defaultImageEditModel();
        return catalogEditModel != null ? catalogEditModel : imageModel;
    }

    private List<String> modelIds(List<MediaModelHint> models) {
        var ids = new ArrayList<String>(models.size());
        for (var model : models) ids.add(model.modelId());
        return List.copyOf(ids);
    }

    private void put(Map<String, Object> variables, String key, String value) {
        if (value != null && !value.isBlank()) variables.put(key, value);
    }

    public record LocalBuiltinTool(String name, ToolCall tool, int timeoutSeconds) {
        public String description() {
            return tool.getDescription();
        }

        public String inputSchema() {
            return JsonUtil.toJson(tool.toJsonSchema());
        }
    }

    private record Providers(MediaProvider media, MediaProvider image, MediaProvider video,
                             MediaModelCatalog catalog, LLMProvider llm) {
    }
}
