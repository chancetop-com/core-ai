package ai.core.server.session;

import ai.core.agent.ExecutionContext;
import ai.core.decision.DecisionProvider;
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
import ai.core.media.SessionMediaKey;
import ai.core.media.SessionMediaStore;
import ai.core.server.settings.SystemSettingsService;
import ai.core.session.BackgroundTaskStore;
import ai.core.tool.ToolCallAsyncTaskManager;
import ai.core.tool.subagent.SubagentOutputSinkFactory;
import ai.core.tool.tools.GenerateImageTool;
import ai.core.tool.tools.GetVideoStatusTool;
import ai.core.tool.tools.InternalUrlResolver;
import ai.core.tool.wait.WaitCapability;

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
    private DecisionProvider decisionProvider;
    private final ApiUserQuotaService quotaService;
    private ToolCallAsyncTaskManager asyncTaskManager;
    private BackgroundTaskStore backgroundTaskStore;
    private SessionMediaStore mediaStore;

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
    public SessionContextBuilder withAsyncTaskManager(ToolCallAsyncTaskManager asyncTaskManager) {
        this.asyncTaskManager = asyncTaskManager;
        return this;
    }

    public SessionContextBuilder withBackgroundTaskStore(BackgroundTaskStore backgroundTaskStore) {
        this.backgroundTaskStore = backgroundTaskStore;
        return this;
    }

    public SessionContextBuilder withDecisionProvider(DecisionProvider decisionProvider) {
        this.decisionProvider = decisionProvider;
        return this;
    }

    /** Session media refs need the store to register uploads and hydrate them per request. */
    public SessionContextBuilder withMediaStore(SessionMediaStore mediaStore) {
        this.mediaStore = mediaStore;
        return this;
    }

    public ExecutionContext build(String sessionId, String userId) {
        return build(sessionId, userId, null);
    }

    /**
     * @param subagentOutputSinkFactory non-null enables server-side background tasks for this session
     *                                   (the session creates a BackgroundTaskManager only when set)
     */
    public ExecutionContext build(String sessionId, String userId, SubagentOutputSinkFactory subagentOutputSinkFactory) {
        // pending tool results are registered against this session; the server drives and notifies them
        var builder = ExecutionContext.builder().sessionId(sessionId).userId(userId)
                .asyncTaskManager(asyncTaskManager)
                .backgroundTaskStore(backgroundTaskStore)
                .waitCapability(WaitCapability.ENABLED)
                .customVariable(InternalUrlResolver.CONTEXT_KEY, new FileDownloadUrlResolver(fileService, publicUrlConfiguration.value()))
                .customVariable(GenerateImageTool.IMAGE_OUTPUT_SINK_CONTEXT_KEY,
                        new ServerImageOutputSink(userId, fileService,
                                artifactSetup.createChatSessionSink(sessionId), publicUrlConfiguration))
                .customVariable(GetVideoStatusTool.VIDEO_OUTPUT_SINK_CONTEXT_KEY,
                        new ServerImageOutputSink(userId, fileService,
                                artifactSetup.createChatSessionSink(sessionId), publicUrlConfiguration))
                .customVariables(mediaModelVariables());
        if (subagentOutputSinkFactory != null) builder.subagentOutputSinkFactory(subagentOutputSinkFactory);
        var context = builder.build();
        if (mediaStore != null) {
            context.getCustomVariables().put(SessionMediaStore.CONTEXT_KEY, mediaStore);
            context.getCustomVariables().put(SessionMediaStore.KEY_CONTEXT_KEY,
                    new SessionMediaKey(sessionId, userId));
        }
        // quota metering fires per LLM call (main turns, sub-agents and tool-internal calls all
        // share this context), so token usage is accounted against the session user synchronously
        context.setTokenCostCallback(usage -> quotaService.recordUsage(userId, usage.getPromptTokens(), usage.getCompletionTokens()));
        context.setDecisionProvider(decisionProvider);
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
