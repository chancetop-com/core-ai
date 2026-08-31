package ai.core.server;

import ai.core.media.audio.AudioProvider;
import ai.core.server.audio.AzureSpeechAudioProvider;
import ai.core.server.render.FileRenderProductStore;
import ai.core.server.render.GatewayRenderBackend;
import ai.core.server.render.RenderBackend;
import ai.core.server.render.RenderCapsResolver;
import ai.core.server.render.RenderProductStore;
import ai.core.server.render.ffmpeg.SandboxFfmpegRunner;
import ai.core.server.render.postprocess.GatewayPostProcessBackend;
import ai.core.server.render.postprocess.PostProcessBackend;
import core.framework.module.Module;

/**
 * Base media capability: generate images and videos through the gateway, persist the products
 * content-addressed, resolve what a model can do from its admin-maintained gateway row, run ffmpeg
 * plans in a sandbox, post-process products, and speak/transcribe. Extracted from the drama pipeline,
 * which was only the first consumer of all of it.
 * <p>
 * Loads after GatewayModule (MediaProvider, GatewayRoutingEngine), ObjectStorageModule (FileService),
 * SettingsModule (SystemSettingsService) and SandboxModule (SandboxService) — bind() resolves eagerly.
 *
 * @author stephen
 */
public class RenderModule extends Module {
    @Override
    protected void initialize() {
        bind(RenderBackend.class, bind(GatewayRenderBackend.class));
        bind(RenderProductStore.class, bind(FileRenderProductStore.class));
        bind(RenderCapsResolver.class);
        bind(SandboxFfmpegRunner.class);
        bind(PostProcessBackend.class, bind(GatewayPostProcessBackend.class));
        bind(AudioProvider.class, bind(AzureSpeechAudioProvider.class));
    }
}
