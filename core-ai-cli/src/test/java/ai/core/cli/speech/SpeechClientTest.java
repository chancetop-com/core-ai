package ai.core.cli.speech;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class SpeechClientTest {
    private HttpServer server;
    private final AtomicReference<String> transcriptionBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/speech/models", exchange -> respond(exchange, """
                {"models":[{"modelId":"whisper-1","displayName":"Whisper","providerName":"azure-openai"}],
                 "defaultModel":"whisper-1","azureConfigured":false}
                """));
        server.createContext("/api/speech/transcriptions", exchange -> {
            transcriptionBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"text":"hello","language":"en","durationSeconds":1.2,"model":"whisper-1",
                     "provider":"azure-openai","segments":[{"text":"hello","start":0.0,"end":1.2}]}
                    """);
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void listsModels() {
        var response = client().models();

        assertEquals("whisper-1", response.defaultModel);
        assertEquals(1, response.models.size());
        assertEquals("Whisper", response.models.getFirst().displayName);
    }

    @Test
    void uploadsAudioWithFormOptions() throws Exception {
        Path audio = Files.createTempFile("speech-client-", ".wav");
        Files.write(audio, new byte[]{1, 2, 3});

        var result = client().transcribe(audio, "zh-CN", "whisper-1", null);

        assertEquals("hello", result.text);
        assertEquals("en", result.language);
        assertEquals(1.2, result.durationSeconds);
        assertEquals(1, result.segments.size());
        var body = transcriptionBody.get();
        assertTrue(body.contains("name=\"model\""));
        assertTrue(body.contains("\r\nwhisper-1\r\n"));
        assertTrue(body.contains("name=\"language\""));
        assertTrue(body.contains("\r\nzh-CN\r\n"));
        assertTrue(body.contains("name=\"audio\"; filename=\"" + audio.getFileName() + "\""));
        assertTrue(body.contains("Content-Type: audio/wav"));
    }

    @Test
    void resolvesContentTypeFromExtension() {
        assertEquals("audio/wav", SpeechClient.contentType(Path.of("a.wav")));
        assertEquals("audio/mpeg", SpeechClient.contentType(Path.of("a.mp3")));
        assertEquals("audio/webm", SpeechClient.contentType(Path.of("a.webm")));
        assertEquals("application/octet-stream", SpeechClient.contentType(Path.of("a.bin")));
    }

    private SpeechClient client() {
        return new SpeechClient(baseUrl(), "test-key", false);
    }

    private String baseUrl() {
        return "http://" + InetAddress.getLoopbackAddress().getHostName() + ":" + server.getAddress().getPort();
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
        var bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
