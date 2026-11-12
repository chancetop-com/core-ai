package ai.core.server.gateway;

import ai.core.media.audio.TranscriptionRequest;
import ai.core.server.domain.GatewayProviderConfig;
import com.sun.net.httpserver.HttpServer;
import core.framework.web.exception.BadRequestException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class GatewayTranscriptionProviderTest {
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/audio/transcriptions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            var response = "{\"text\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void routesToTheUpstreamModelAndSendsTheProviderKey() {
        var provider = provider("openai", "OPENAI_IMAGES");
        var routing = mock(GatewayRoutingEngine.class);
        when(routing.route("whisper-1", GatewayEndpointType.AUDIO_TRANSCRIPTION))
                .thenReturn(new GatewayRoute(provider, "whisper-1"));

        var result = provider(routing).transcribe(new TranscriptionRequest("whisper-1",
                new byte[]{1, 2}, "audio/wav", "zh", null));

        assertEquals("ok", result.text());
        assertEquals("Bearer provider-key", authorization.get());
        assertTrue(body.get().contains("\r\nwhisper-1\r\n"));
    }

    @Test
    void rejectsProvidersThatDoNotSpeakTheOpenAiAudioProtocol() {
        var provider = provider("kie", "KIE");
        var routing = mock(GatewayRoutingEngine.class);
        when(routing.route("whisper-1", GatewayEndpointType.AUDIO_TRANSCRIPTION))
                .thenReturn(new GatewayRoute(provider, "whisper-1"));

        var error = assertThrows(BadRequestException.class, () -> provider(routing).transcribe(
                new TranscriptionRequest("whisper-1", new byte[]{1}, "audio/wav", null, null)));

        assertTrue(error.getMessage().contains("does not support audio transcription"));
    }

    private GatewayTranscriptionProvider provider(GatewayRoutingEngine routing) {
        var provider = new GatewayTranscriptionProvider();
        provider.routingEngine = routing;
        provider.secretProtector = new GatewaySecretProtector("test-secret");
        return provider;
    }

    private GatewayProviderConfig provider(String type, String mediaProtocol) {
        var protector = new GatewaySecretProtector("test-secret");
        var provider = new GatewayProviderConfig();
        provider.id = "provider-1";
        provider.name = "test";
        provider.type = type;
        provider.mediaProtocol = mediaProtocol;
        provider.baseUrl = baseUrl();
        provider.apiKeyEncrypted = protector.protect("provider-key");
        return provider;
    }

    private String baseUrl() {
        return "http://" + InetAddress.getLoopbackAddress().getHostName() + ":" + server.getAddress().getPort();
    }
}
