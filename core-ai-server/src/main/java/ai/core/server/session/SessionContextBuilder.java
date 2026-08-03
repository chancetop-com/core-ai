package ai.core.server.session;

import ai.core.agent.ExecutionContext;
import ai.core.server.artifact.ChatArtifactSetup;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.artifact.ServerImageOutputSink;
import ai.core.server.file.FileDownloadUrlResolver;
import ai.core.server.file.FileService;
import ai.core.server.settings.SystemSettingsService;
import ai.core.tool.tools.GenerateImageTool;
import ai.core.tool.tools.GetVideoStatusTool;
import ai.core.tool.tools.InternalUrlResolver;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds session execution contexts with file/media sinks and media model variables.
 * Shared by the session creation and rebuild paths.
 *
 * @author stephen
 */
public final class SessionContextBuilder {
    public static ExecutionContext build(String sessionId, String userId, ChatArtifactSetup artifactSetup,
                                         FileService fileService, PublicUrlConfiguration publicUrlConfiguration,
                                         SystemSettingsService systemSettingsService) {
        return ExecutionContext.builder().sessionId(sessionId).userId(userId)
                .customVariable(InternalUrlResolver.CONTEXT_KEY, new FileDownloadUrlResolver(fileService, publicUrlConfiguration.value()))
                .customVariable(GenerateImageTool.IMAGE_OUTPUT_SINK_CONTEXT_KEY,
                        new ServerImageOutputSink(userId, fileService,
                                artifactSetup.createChatSessionSink(sessionId), publicUrlConfiguration))
                .customVariable(GetVideoStatusTool.VIDEO_OUTPUT_SINK_CONTEXT_KEY,
                        new ServerImageOutputSink(userId, fileService,
                                artifactSetup.createChatSessionSink(sessionId), publicUrlConfiguration))
                .customVariables(mediaModelVariables(systemSettingsService))
                .build();
    }

    private static Map<String, Object> mediaModelVariables(SystemSettingsService systemSettingsService) {
        var variables = new HashMap<String, Object>();
        putModel(variables, "media.caption.model", systemSettingsService.captionImageModel());
        putModel(variables, "media.image.model", systemSettingsService.imageGenerationModel());
        putModel(variables, "media.video.model", systemSettingsService.videoGenerationModel());
        return variables;
    }

    private static void putModel(Map<String, Object> variables, String key, String model) {
        if (model != null) variables.put(key, model);
    }

    private SessionContextBuilder() {
    }
}
