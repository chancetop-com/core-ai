package ai.core.cli.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Body encoding as seen through the JDK HTTP client: a gateway in front of the server can drop,
 * duplicate or keep a stale {@code Content-Encoding}, and the client has to read the bytes it got
 * instead of trusting that header.
 *
 * @author stephen
 */
class RemoteApiClientBodyEncodingTest {
    private static final String JSON = "{\"servers\":[{\"name\":\"stub\"}]}";
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private static HttpServer server;
    private static String baseUrl;

    private static void respond(HttpExchange exchange, int status, byte[] body, String contentEncoding) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (contentEncoding != null) {
            exchange.getResponseHeaders().set("Content-Encoding", contentEncoding);
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gzip(byte[] body) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(out)) {
            gzip.write(body);
        }
        return out.toByteArray();
    }

    private static byte[] truncate(byte[] body) {
        var truncated = new byte[10];
        System.arraycopy(body, 0, truncated, 0, truncated.length);
        return truncated;
    }

    private static void route(String path, int status, byte[] body, String contentEncoding) {
        server.createContext(path, exchange -> respond(exchange, status, body, contentEncoding));
    }

    private static RemoteApiClient client() {
        return new RemoteApiClient(baseUrl, "key");
    }

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        route("/gzip-undeclared", 200, gzip(bytes(JSON)), null);
        route("/gzip-double", 200, gzip(gzip(bytes(JSON))), "gzip");
        route("/gzip-stale-header", 200, bytes(JSON), "gzip");
        route("/gzip-truncated", 200, truncate(gzip(bytes(JSON))), "gzip");
        route("/binary", 200, PNG, null);
        route("/error-stale-gzip-header", 500, bytes("{\"message\":\"boom\"}"), "gzip");
        route("/error-binary", 502, PNG, null);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    // the reported failure: the client asks for gzip, the gateway answers gzip without the header,
    // and the raw body reached Jackson as a leading 0x1F ("Illegal character ((CTRL-CHAR, code 31))")
    @Test
    void readsGzipWithoutContentEncoding() {
        assertEquals(JSON, client().getRequired("/gzip-undeclared"));
    }

    @Test
    void readsDoubleGzip() {
        assertEquals(JSON, client().getRequired("/gzip-double"));
    }

    @Test
    void readsPlainBodyBehindStaleGzipHeader() {
        assertEquals(JSON, client().getRequired("/gzip-stale-header"));
    }

    @Test
    void rejectsBinaryBodyWithReadableError() {
        var error = assertThrows(ResponseBodyException.class, () -> client().getRequired("/binary"));
        assertTrue(error.getMessage().contains("not UTF-8 text"), error.getMessage());
        assertFalse(error.getMessage().contains("CTRL-CHAR"), error.getMessage());
    }

    @Test
    void rejectsTruncatedGzipWithReadableError() {
        var error = assertThrows(ResponseBodyException.class, () -> client().getRequired("/gzip-truncated"));
        assertTrue(error.getMessage().contains("gzip"), error.getMessage());
    }

    @Test
    void keepsHttpStatusWhenErrorBodyHasStaleGzipHeader() {
        var error = assertThrows(RemoteApiException.class, () -> client().getRequired("/error-stale-gzip-header"));
        assertEquals(500, error.statusCode);
        assertEquals("boom", error.getMessage());
    }

    @Test
    void keepsHttpStatusWhenErrorBodyIsBinary() {
        var error = assertThrows(RemoteApiException.class, () -> client().getRequired("/error-binary"));
        assertEquals(502, error.statusCode);
        assertTrue(error.getMessage().contains("502"), error.getMessage());
    }
}
