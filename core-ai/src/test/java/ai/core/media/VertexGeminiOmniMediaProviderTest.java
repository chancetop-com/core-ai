package ai.core.media;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author stephen
 */
class VertexGeminiOmniMediaProviderTest {
    private HttpServer server;
    private VertexGeminiOmniMediaProvider provider;
    private final AtomicReference<String> interactionBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/projects/test-project/locations/global/interactions/video-1", exchange -> {
            var bytes = interactionBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        var baseUrl = "http://localhost:" + server.getAddress().getPort();
        provider = new VertexGeminiOmniMediaProvider(baseUrl, "test-project", "global", new GoogleAccessTokenProvider(null) {
            @Override
            public String accessToken() {
                return "test-token";
            }
        });
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void failedInteractionSurfacesTheErrorsArray() {
        interactionBody.set("""
            {"id":"video-1","status":"failed","object":"interaction",
             "errors":[{"message":"Unable to show the generated video. Try rephrasing the prompt.","code":"content_blocked"}]}""");

        var status = provider.getVideoStatus("video-1");

        assertEquals("failed", status.status());
        assertEquals("content_blocked: Unable to show the generated video. Try rephrasing the prompt.", status.error());
    }

    @Test
    void failedInteractionWithTopLevelErrorObjectStillWorks() {
        interactionBody.set("""
            {"id":"video-1","status":"failed","error":{"message":"quota exceeded"}}""");

        var status = provider.getVideoStatus("video-1");

        assertEquals("failed", status.status());
        assertEquals("quota exceeded", status.error());
    }

    @Test
    void processingInteractionHasNoError() {
        interactionBody.set("""
            {"id":"video-1","status":"in_progress"}""");

        var status = provider.getVideoStatus("video-1");

        assertEquals("processing", status.status());
        assertNull(status.error());
    }
}
