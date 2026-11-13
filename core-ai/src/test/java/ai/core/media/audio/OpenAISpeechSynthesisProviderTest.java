package ai.core.media.audio;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class OpenAISpeechSynthesisProviderTest {
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> apiKey = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/audio/speech", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange);
        });
        server.createContext("/openai/deployments/tts-1/audio/speech", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKey.set(exchange.getRequestHeaders().getFirst("api-key"));
            query.set(exchange.getRequestURI().getQuery());
            respond(exchange);
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void openAiUsesTheAudioSpeechSurface() {
        var result = new OpenAISpeechSynthesisProvider(baseUrl(), "test-key")
                .synthesize(new SpeechSynthesisRequest("tts-1", "hello", "alloy", "en", "mp3", 1.2, null));

        assertEquals(3, result.audio().length);
        assertEquals("audio/mpeg", result.contentType());
        assertEquals("alloy", result.voice());
        assertTrue(body.get().contains("\"model\":\"tts-1\""));
        assertTrue(body.get().contains("\"input\":\"hello\""));
        assertTrue(body.get().contains("\"voice\":\"alloy\""));
        assertTrue(body.get().contains("\"response_format\":\"mp3\""));
        assertTrue(body.get().contains("\"speed\":1.2"));
        assertEquals("Bearer test-key", authorization.get());
    }

    @Test
    void azureUsesTheDeploymentPathWithoutModelInTheBody() {
        var result = OpenAISpeechSynthesisProvider.azure(baseUrl(), "azure-key", "2024-10-21")
                .synthesize(new SpeechSynthesisRequest("tts-1", "hello", null, "en", "wav", null, null));

        assertEquals(3, result.audio().length);
        assertEquals("audio/wav", result.contentType());
        assertEquals("alloy", result.voice());
        assertFalse(body.get().contains("\"model\""));
        assertTrue(body.get().contains("\"response_format\":\"wav\""));
        assertEquals("azure-key", apiKey.get());
        assertEquals("api-version=2024-10-21", query.get());
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        var bytes = new byte[]{1, 2, 3};
        exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String baseUrl() {
        return "http://" + InetAddress.getLoopbackAddress().getHostName() + ":" + server.getAddress().getPort();
    }
}
