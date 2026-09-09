package ai.core.server;

import ai.core.server.web.SpaRoutes;
import ai.core.server.web.StaticFileController;
import ai.core.server.web.WebAssetsUploader;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

import java.nio.file.Path;

/**
 * @author stephen
 */
public class WebModule extends Module {

    private static final String[] ROOT_FILES = {"/favicon.svg", "/favicon.ico", "/icons.svg", "/logo-lockup.svg", "/logo-lockup-dark.svg"};

    @Override
    protected void initialize() {
        registerStaticFiles();
    }

    private void registerWebAssetsUploader(Path webDir, StaticFileController controller) {
        var uploader = bind(WebAssetsUploader.class);
        onStartup(() -> uploader.upload(webDir, controller));
    }

    private void registerStaticFiles() {
        var webPath = System.getProperty("core.webPath");
        if (webPath == null) return;
        var webDir = Path.of(webPath);
        if (!webDir.toFile().exists()) return;
        var controller = new StaticFileController(webDir);
        registerWebAssetsUploader(webDir, controller);
        for (var path : ROOT_FILES) {
            http().route(HTTPMethod.GET, path, controller::serve);
        }
        http().route(HTTPMethod.GET, "/apple-touch-icon.png", controller::serveAppleTouchIcon);
        // iOS Safari legacy probe; reuse favicon.svg to silence 404 noise.
        http().route(HTTPMethod.GET, "/apple-touch-icon-precomposed.png", controller::serveAppleTouchIcon);
        http().route(HTTPMethod.GET, "/assets/:file", controller::serve);
        for (var path : SpaRoutes.PATHS) {
            http().route(HTTPMethod.GET, path, controller::serve);
        }
    }
}
