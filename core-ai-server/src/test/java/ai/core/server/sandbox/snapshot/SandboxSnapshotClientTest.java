package ai.core.server.sandbox.snapshot;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SandboxSnapshotClientTest {
    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test
    void captureStreamsBodyToFileAndComputesSha() throws Exception {
        var body = "fake-tar-gz-bytes".getBytes(StandardCharsets.UTF_8);
        server.createContext("/snapshot", exchange -> {
            exchange.getResponseHeaders().set("X-Snapshot-File-Count", "42");
            exchange.getResponseHeaders().set("X-Snapshot-Runtime-Version", "1.0.27");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        var target = Files.createTempFile("capture-test-", ".tar.gz");
        try {
            var result = new SandboxSnapshotClient().capture("127.0.0.1", port, target);
            assertEquals(sha256Hex(body), result.sha256());
            assertEquals(body.length, result.sizeBytes());
            assertEquals(42, result.fileCount());
            assertEquals("1.0.27", result.runtimeVersion());
            assertEquals(body.length, Files.size(target));
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void captureFailsOnNon200() throws Exception {
        server.createContext("/snapshot", exchange -> {
            var msg = "{\"status\":\"error\",\"error\":\"too big\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(413, msg.length);
            exchange.getResponseBody().write(msg);
            exchange.close();
        });
        var target = Files.createTempFile("capture-test-", ".tar.gz");
        try {
            assertThrows(RuntimeException.class, () -> new SandboxSnapshotClient().capture("127.0.0.1", port, target));
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void restoreSendsShaHeaderAndBody() throws Exception {
        var receivedSha = new AtomicReference<String>();
        var receivedBody = new AtomicReference<byte[]>();
        server.createContext("/snapshot/restore", exchange -> {
            receivedSha.set(exchange.getRequestHeaders().getFirst("X-Snapshot-Sha256"));
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            var ok = "{\"status\":\"ok\",\"files\":3}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        var tarFile = Files.createTempFile("restore-test-", ".tar.gz");
        Files.writeString(tarFile, "payload");
        try {
            new SandboxSnapshotClient().restore("127.0.0.1", port, tarFile, "abc123");
            assertEquals("abc123", receivedSha.get());
            assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8), receivedBody.get());
        } finally {
            Files.deleteIfExists(tarFile);
        }
    }

    @Test
    void fetchRuntimeVersionParsesHealth() {
        server.createContext("/health", exchange -> {
            var body = "{\"status\":\"ok\",\"version\":\"1.2.3\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        assertEquals("1.2.3", new SandboxSnapshotClient().fetchRuntimeVersion("127.0.0.1", port));
    }

    @Test
    void fetchRuntimeVersionReturnsUnknownOnMalformedBody() {
        server.createContext("/health", exchange -> {
            var body = "not-json".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        assertEquals("unknown", new SandboxSnapshotClient().fetchRuntimeVersion("127.0.0.1", port));
    }
}
