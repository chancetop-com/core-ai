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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class OpenAIAudioTranscriptionClientTest {
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> apiKey = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicBoolean retryMode = new AtomicBoolean();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/audio/transcriptions", exchange -> {
            calls.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange);
        });
        server.createContext("/openai/deployments/whisper/audio/transcriptions", exchange -> {
            calls.incrementAndGet();
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
    void whisperUsesVerboseJsonAndParsesSegments() {
        responseBody.set("""
                {"text":"hello world","language":"english","duration":2.5,
                 "segments":[{"start":0.0,"end":1.2,"text":"hello"},{"start":1.2,"end":2.5,"text":"world"}]}
                """);

        var result = client().transcribe(request("whisper-1", "zh-CN"));

        assertEquals("hello world", result.text());
        assertEquals("english", result.language());
        assertEquals(2.5, result.durationSeconds());
        assertEquals(2, result.segments().size());
        assertEquals("hello", result.segments().getFirst().text());
        assertEquals(1.2, result.segments().getFirst().endSec());
        assertTrue(body.get().contains("name=\"response_format\""));
        assertTrue(body.get().contains("verbose_json"));
        assertTrue(body.get().contains("name=\"language\""));
        assertTrue(body.get().contains("\r\nzh\r\n"));
        assertTrue(body.get().contains("name=\"file\"; filename=\"audio.wav\""));
        assertEquals("Bearer test-key", authorization.get());
    }

    @Test
    void nonWhisperModelsUseJsonWithoutSegments() {
        responseBody.set("{\"text\":\"hi\"}");

        var result = client().transcribe(request("gpt-4o-transcribe", null));

        assertEquals("hi", result.text());
        assertNull(result.language());
        assertTrue(result.segments().isEmpty());
        assertTrue(body.get().contains("name=\"response_format\""));
        assertTrue(body.get().contains("\r\njson\r\n"));
    }

    @Test
    void diarizationModelsUseDiarizedJsonAndParseSpeaker() {
        responseBody.set("""
                {"text":"hello","segments":[{"speaker":"A","start":0.0,"end":1.1,"text":"hello"}]}
                """);

        var result = client().transcribe(request("gpt-4o-transcribe-diarize", null));

        assertEquals(1, result.segments().size());
        assertEquals("A", result.segments().getFirst().speaker());
        assertEquals(1.1, result.segments().getFirst().endSec());
        assertTrue(body.get().contains("diarized_json"));
    }

    @Test
    void retriesWithoutVerboseJsonWhenTheModelRejectsIt() {
        retryMode.set(true);

        var result = client().transcribe(request("whisper-1", null));

        assertEquals("retried", result.text());
        assertEquals(2, calls.get());
    }

    @Test
    void azureUsesDeploymentPathAndApiKeyHeader() {
        responseBody.set("{\"text\":\"azure text\"}");

        var result = OpenAIAudioTranscriptionClient.azure(baseUrl(), "azure-key", "2024-10-21")
                .transcribe(request("whisper", "zh"));

        assertEquals("azure text", result.text());
        assertEquals("azure-key", apiKey.get());
        assertEquals("api-version=2024-10-21", query.get());
        assertTrue(body.get().contains("name=\"file\"; filename=\"audio.wav\""));
        assertFalse(body.get().contains("name=\"model\""));
    }

    @Test
    void upstreamErrorIsReportedWithBody() {
        status.set(401);
        responseBody.set("{\"error\":{\"message\":\"bad key\"}}");

        var error = assertThrows(TranscriptionException.class,
                () -> client().transcribe(request("whisper-1", null)));

        assertTrue(error.getMessage().contains("401"));
        assertTrue(error.getMessage().contains("bad key"));
    }

    private OpenAIAudioTranscriptionClient client() {
        return new OpenAIAudioTranscriptionClient(baseUrl(), "test-key");
    }

    private TranscriptionRequest request(String model, String language) {
        return new TranscriptionRequest(model, new byte[]{1, 2, 3}, "audio/wav", language, null);
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        var call = calls.get();
        var code = status.get();
        var response = responseBody.get();
        if (retryMode.get() && call == 1) {
            code = 400;
            response = "{\"error\":{\"message\":\"response_format verbose_json is not supported\"}}";
        } else if (retryMode.get()) {
            code = 200;
            response = "{\"text\":\"retried\"}";
        }
        var bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String baseUrl() {
        return "http://" + InetAddress.getLoopbackAddress().getHostName() + ":" + server.getAddress().getPort();
    }
}
