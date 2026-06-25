package ai.core.server.web;

import core.framework.api.http.HTTPStatus;
import core.framework.http.ContentType;
import core.framework.web.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StaticFileControllerTest {
    private static final ContentType APPLICATION_JS = ContentType.parse("application/javascript");
    private static final String CACHE_CONTROL = "Cache-Control";

    @TempDir
    Path webDir;

    @Test
    void fallsBackToIndexForSpaRoutes() throws IOException {
        Files.writeString(webDir.resolve("index.html"), "<html></html>");
        var controller = new StaticFileController(webDir);

        var response = controller.serve(request("/workflows"));

        assertEquals(HTTPStatus.OK, response.status());
        assertEquals(ContentType.TEXT_HTML, response.contentType().orElseThrow());
        assertEquals("no-cache, no-store, must-revalidate", response.header(CACHE_CONTROL).orElseThrow());
    }

    @Test
    void servesStaticAssetWithoutSpaFallback() throws IOException {
        var assetsDir = webDir.resolve("assets");
        Files.createDirectories(assetsDir);
        Files.writeString(assetsDir.resolve("index-abc123.js"), "console.log('ok');");
        var controller = new StaticFileController(webDir);

        var response = controller.serve(request("/assets/index-abc123.js"));

        assertEquals(HTTPStatus.OK, response.status());
        assertEquals(APPLICATION_JS, response.contentType().orElseThrow());
        assertEquals("public, max-age=31536000, immutable", response.header(CACHE_CONTROL).orElseThrow());
    }

    @Test
    void returnsNotFoundForMissingStaticAsset() throws IOException {
        Files.writeString(webDir.resolve("index.html"), "<html></html>");
        var controller = new StaticFileController(webDir);

        var response = controller.serve(request("/assets/index-old.js"));

        assertEquals(HTTPStatus.NOT_FOUND, response.status());
        assertEquals(ContentType.TEXT_PLAIN, response.contentType().orElseThrow());
        assertTrue(response.header(CACHE_CONTROL).isEmpty());
    }

    private Request request(String path) {
        var request = mock(Request.class);
        when(request.path()).thenReturn(path);
        return request;
    }
}
