package ai.core.server.web;

import core.framework.http.ContentType;
import core.framework.web.Request;
import core.framework.web.Response;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author Xander
 */
public class StaticFileController {
    private static final ContentType TEXT_CSS = ContentType.parse("text/css");
    private static final ContentType IMAGE_SVG = ContentType.parse("image/svg+xml");
    private static final ContentType APPLICATION_JS = ContentType.parse("application/javascript");

    private final Path webDir;

    public StaticFileController(Path webDir) {
        this.webDir = webDir;
    }

    public Response serve(Request request) {
        var path = request.path();
        if ("/".equals(path)) path = "/index.html";

        var file = webDir.resolve(path.substring(1)).normalize();
        if (!file.startsWith(webDir) || !Files.exists(file) || Files.isDirectory(file)) {
            // SPA fallback: return index.html for non-API routes
            file = webDir.resolve("index.html");
        }
        if (!Files.exists(file)) {
            return Response.text("not found").status(core.framework.api.http.HTTPStatus.NOT_FOUND);
        }

        try {
            var bytes = Files.readAllBytes(file);
            var contentType = resolveContentType(file.toString());
            return Response.bytes(bytes).contentType(contentType);
        } catch (Exception e) {
            return Response.text("error").status(core.framework.api.http.HTTPStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private ContentType resolveContentType(String path) {
        if (path.endsWith(".html")) return ContentType.TEXT_HTML;
        if (path.endsWith(".js")) return APPLICATION_JS;
        if (path.endsWith(".css")) return TEXT_CSS;
        if (path.endsWith(".svg")) return IMAGE_SVG;
        if (path.endsWith(".png")) return ContentType.IMAGE_PNG;
        return ContentType.APPLICATION_OCTET_STREAM;
    }
}
