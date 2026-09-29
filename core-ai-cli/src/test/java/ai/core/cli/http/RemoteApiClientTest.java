package ai.core.cli.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteApiClientTest {
    private static final String[] URLS = {"https://localhost:8443", "https://127.0.0.1:8443", "https://[::1]:8443", "https://example.com"};
    private static final AtomicReference<String> ACCEPT_ENCODING = new AtomicReference<>();
    private static HttpServer server;
    private static String baseUrl;

    private static byte[] gzip(byte[] body) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(output)) {
            gzip.write(body);
        }
        return output.toByteArray();
    }

    private static void respond(HttpExchange exchange, byte[] body, boolean gzipped, int status) throws IOException {
        if (gzipped) exchange.getResponseHeaders().add("Content-Encoding", "gzip");
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gzip", exchange -> {
            ACCEPT_ENCODING.set(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
            respond(exchange, gzip("{\"ok\":true}".getBytes(StandardCharsets.UTF_8)), true, 200);
        });
        server.createContext("/plain", exchange -> {
            ACCEPT_ENCODING.set(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
            respond(exchange, "{\"ok\":\"uncompressed\"}".getBytes(StandardCharsets.UTF_8), false, 200);
        });
        server.createContext("/gzip-error", exchange -> {
            ACCEPT_ENCODING.set(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
            respond(exchange, gzip("{\"message\":\"permission denied\"}".getBytes(StandardCharsets.UTF_8)), true, 403);
        });
        server.createContext("/binary", exchange -> {
            ACCEPT_ENCODING.set(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
            respond(exchange, new byte[]{1, 2, 3, 4}, false, 200);
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Test
    void textRequestsAskForGzipAndInflateTheAnswer() {
        var client = new RemoteApiClient(baseUrl, "key");

        assertEquals("{\"ok\":true}", client.getRequired("/gzip"));
        assertEquals("gzip", ACCEPT_ENCODING.get());
    }

    @Test
    void aPlainAnswerStillReadsAsText() {
        var client = new RemoteApiClient(baseUrl, "key");

        assertEquals("{\"ok\":\"uncompressed\"}", client.getRequired("/plain"));
    }

    @Test
    void anErrorBodyIsInflatedToo() {
        var client = new RemoteApiClient(baseUrl, "key");

        var error = assertThrows(RemoteApiException.class, () -> client.getRequired("/gzip-error"));

        assertEquals(403, error.statusCode);
        assertTrue(error.getMessage().contains("permission denied"), error.getMessage());
    }

    @Test
    void binaryDownloadsDoNotAskForCompression() {
        var client = new RemoteApiClient(baseUrl, "key");

        var response = client.getBytes("/binary");

        assertEquals(4, response.body().length);
        assertNull(ACCEPT_ENCODING.get(), "a binary download must not carry Accept-Encoding");
    }

    @Test
    void defaultKeepsCertificateVerificationForAnyHost() throws Exception {
        for (var url : URLS) {
            var client = new RemoteApiClient(url, "key");
            assertSame(SSLContext.getDefault(), client.httpClient().sslContext(), url);
        }
    }

    @Test
    void insecureFlagDisablesVerificationForAnyHost() throws Exception {
        for (var url : URLS) {
            var client = new RemoteApiClient(url, "key", null, Map.of(), true);
            assertNotEquals(SSLContext.getDefault(), client.httpClient().sslContext(), url);
        }
    }
}
