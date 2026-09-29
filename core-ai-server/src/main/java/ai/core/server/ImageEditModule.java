package ai.core.server;

import ai.core.api.server.media.ImageEditWebService;
import ai.core.server.media.ImageEditService;
import ai.core.server.media.ImageEditWebServiceImpl;
import core.framework.module.Module;

/**
 * Region edit (paint a region on an image, repaint only that region) for the canvas UI.
 * <p>
 * Loads after GatewayModule (MediaProvider, MediaJobService, GatewayRoutingEngine), ObjectStorageModule
 * (FileService) and SettingsModule (SystemSettingsService): bind() resolves @Inject eagerly.
 *
 * @author stephen
 */
public class ImageEditModule extends Module {
    @Override
    protected void initialize() {
        bind(ImageEditService.class);
        api().service(ImageEditWebService.class, bind(ImageEditWebServiceImpl.class));
    }
}
