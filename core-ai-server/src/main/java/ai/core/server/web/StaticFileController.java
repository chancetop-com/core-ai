package ai.core.server.web;

import core.framework.api.http.HTTPStatus;
import core.framework.http.ContentType;
import core.framework.web.Request;
import core.framework.web.Response;

import java.io.UncheckedIOException;
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
            return Response.text("not found").status(HTTPStatus.NOT_FOUND);
        }

        var contentType = resolveContentType(file.toString());
        try {
            return Response.file(file).contentType(contentType);
        } catch (UncheckedIOException e) {
            return Response.text("error").status(HTTPStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // Serve the requested apple-touch-icon file if present; otherwise fall back
    // to favicon.svg so iOS Safari / legacy crawler probes do not produce 404 noise.
    // Skips SPA fallback to avoid returning HTML for image requests.
    public Response serveAppleTouchIcon(Request request) {
        var path = request.path();
        var requested = webDir.resolve(path.substring(1)).normalize();
        if (requested.startsWith(webDir) && Files.exists(requested) && !Files.isDirectory(requested)) {
            try {
                return Response.file(requested).contentType(ContentType.IMAGE_PNG);
            } catch (UncheckedIOException ignored) {
                // fall through to favicon fallback
            }
        }
        var fallback = webDir.resolve("favicon.svg");
        if (!Files.exists(fallback)) {
            return Response.empty().status(HTTPStatus.NO_CONTENT);
        }
        try {
            return Response.file(fallback).contentType(IMAGE_SVG);
        } catch (UncheckedIOException e) {
            return Response.empty().status(HTTPStatus.NO_CONTENT);
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
