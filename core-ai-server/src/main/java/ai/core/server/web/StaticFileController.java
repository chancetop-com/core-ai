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
    private static final String CACHE_CONTROL = "Cache-Control";
    private static final String NO_CACHE = "no-cache, no-store, must-revalidate";
    private static final String IMMUTABLE_CACHE = "public, max-age=31536000, immutable";

    private final Path webDir;

    public StaticFileController(Path webDir) {
        this.webDir = webDir;
    }

    public Response serve(Request request) {
        var path = request.path();
        if ("/".equals(path)) path = "/index.html";

        var file = webDir.resolve(path.substring(1)).normalize();
        if (!file.startsWith(webDir) || !Files.exists(file) || Files.isDirectory(file)) {
            if (path.startsWith("/assets/")) {
                return notFound();
            }
            // SPA fallback: return index.html for non-API routes
            file = webDir.resolve("index.html");
        }
        if (!Files.exists(file)) {
            return notFound();
        }

        var contentType = resolveContentType(file.toString());
        try {
            return withCacheHeaders(Response.file(file).contentType(contentType), path, file);
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

    private Response notFound() {
        return Response.text("not found").status(HTTPStatus.NOT_FOUND);
    }

    private Response withCacheHeaders(Response response, String requestPath, Path file) {
        if (file.getFileName().toString().endsWith(".html")) {
            return response.header(CACHE_CONTROL, NO_CACHE);
        }
        if (requestPath.startsWith("/assets/")) {
            return response.header(CACHE_CONTROL, IMMUTABLE_CACHE);
        }
        return response;
    }
}
