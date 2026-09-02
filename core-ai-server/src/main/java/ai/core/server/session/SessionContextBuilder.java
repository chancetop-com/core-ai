package ai.core.server.session;

import ai.core.agent.ExecutionContext;
import ai.core.media.MediaProvider;
import ai.core.server.apiuser.ApiUserQuotaService;
import ai.core.server.artifact.ChatArtifactSetup;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.artifact.ServerImageOutputSink;
import ai.core.server.file.FileDownloadUrlResolver;
import ai.core.server.file.FileService;
import ai.core.server.gateway.ContextualMediaProvider;
import ai.core.server.gateway.GatewayMediaProvider;
import ai.core.server.gateway.MediaJobOwner;
import ai.core.server.settings.SystemSettingsService;
import ai.core.tool.tools.GenerateImageTool;
import ai.core.tool.tools.GetVideoStatusTool;
import ai.core.tool.tools.InternalUrlResolver;

import java.util.HashMap;
import java.util.Map;

/**
 * Single entry point for session execution contexts: file/media sinks, media model variables and
 * media providers are all wired here, so session creation and session rebuild always produce
 * equivalent contexts.
 *
 * @author stephen
 */
public final class SessionContextBuilder {
    private final ChatArtifactSetup artifactSetup;
    private final FileService fileService;
    private final PublicUrlConfiguration publicUrlConfiguration;
    private final SystemSettingsService systemSettingsService;
    private final MediaProvider mediaProvider;
    private final ApiUserQuotaService quotaService;
    private ai.core.tool.ToolCallAsyncTaskManager asyncTaskManager;

    public SessionContextBuilder(ChatArtifactSetup artifactSetup, FileService fileService,
                                 PublicUrlConfiguration publicUrlConfiguration,
                                 SystemSettingsService systemSettingsService, MediaProvider mediaProvider,
                                 ApiUserQuotaService quotaService) {
        this.artifactSetup = artifactSetup;
        this.fileService = fileService;
        this.publicUrlConfiguration = publicUrlConfiguration;
        this.systemSettingsService = systemSettingsService;
        this.mediaProvider = mediaProvider;
        this.quotaService = quotaService;
    }

    /** Registry for pending tool results; the server drives and notifies them (see AsyncToolTaskService). */
    public SessionContextBuilder withAsyncTaskManager(ai.core.tool.ToolCallAsyncTaskManager asyncTaskManager) {
        this.asyncTaskManager = asyncTaskManager;
        return this;
    }

    public ExecutionContext build(String sessionId, String userId) {
        // pending tool results are registered against this session; the server drives and notifies them
        var context = ExecutionContext.builder().sessionId(sessionId).userId(userId).asyncTaskManager(asyncTaskManager)
                .customVariable(InternalUrlResolver.CONTEXT_KEY, new FileDownloadUrlResolver(fileService, publicUrlConfiguration.value()))
                .customVariable(GenerateImageTool.IMAGE_OUTPUT_SINK_CONTEXT_KEY,
                        new ServerImageOutputSink(userId, fileService,
                                artifactSetup.createChatSessionSink(sessionId), publicUrlConfiguration))
                .customVariable(GetVideoStatusTool.VIDEO_OUTPUT_SINK_CONTEXT_KEY,
                        new ServerImageOutputSink(userId, fileService,
                                artifactSetup.createChatSessionSink(sessionId), publicUrlConfiguration))
                .customVariables(mediaModelVariables())
                .build();
        // quota metering fires per LLM call (main turns, sub-agents and tool-internal calls all
        // share this context), so token usage is accounted against the session user synchronously
        context.setTokenCostCallback(usage -> quotaService.recordUsage(userId, usage.getPromptTokens(), usage.getCompletionTokens()));
        if (mediaProvider instanceof GatewayMediaProvider gatewayMediaProvider) {
            var contextualProvider = new ContextualMediaProvider(gatewayMediaProvider, new MediaJobOwner(userId, sessionId, null));
            context.setImageMediaProvider(contextualProvider);
            context.setVideoMediaProvider(contextualProvider);
        } else if (mediaProvider != null) {
            context.setImageMediaProvider(mediaProvider);
            context.setVideoMediaProvider(mediaProvider);
        }
        return context;
    }

    private Map<String, Object> mediaModelVariables() {
        var variables = new HashMap<String, Object>();
        putModel(variables, "media.caption.model", systemSettingsService.captionImageModel());
        putModel(variables, "media.summarize_pdf.model", systemSettingsService.summarizePdfModel());
        putModel(variables, "media.image.model", systemSettingsService.imageGenerationModel());
        putModel(variables, "media.video.model", systemSettingsService.videoGenerationModel());
        return variables;
    }

    private void putModel(Map<String, Object> variables, String key, String model) {
        if (model != null) variables.put(key, model);
    }
}
