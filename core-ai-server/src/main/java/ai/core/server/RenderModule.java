package ai.core.server;

import ai.core.server.render.FileRenderProductStore;
import ai.core.server.render.GatewayRenderBackend;
import ai.core.server.render.RenderBackend;
import ai.core.server.render.RenderCapsResolver;
import ai.core.server.render.RenderProductStore;
import ai.core.server.render.ffmpeg.SandboxFfmpegRunner;
import core.framework.module.Module;

/**
 * Base media-render capability: generate images and videos through the gateway, persist the products
 * content-addressed, resolve what a model can do from its admin-maintained gateway row, and run
 * ffmpeg plans in a sandbox. Extracted from the drama pipeline, which was only its first consumer.
 * <p>
 * Loads after GatewayModule (MediaProvider, GatewayRoutingEngine), ObjectStorageModule (FileService)
 * and SandboxModule (SandboxService) — core-ng resolves bind() eagerly.
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
    }
}
