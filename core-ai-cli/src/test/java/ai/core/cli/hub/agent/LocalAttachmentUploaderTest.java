package ai.core.cli.hub.agent;

import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubExitCodes;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire contract of a local attachment upload: which credential the file requests, where the bytes
 * are PUT, and what the resulting attachment carries — stubbed with a real HTTP server so the request
 * shape is exercised for real.
 *
 * @author stephen
 */
class LocalAttachmentUploaderTest {
    @TempDir
    Path tempDir;

    private final AtomicReference<Captured> credentialRequest = new AtomicReference<>();
    private final AtomicReference<Captured> uploadRequest = new AtomicReference<>();
    private HttpServer server;
    private int uploadStatus = 201;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/blob/upload-credential", exchange -> {
            credentialRequest.set(capture(exchange));
            var port = server.getAddress().getPort();
            respond(exchange, 200, "{\"upload_url\":\"http://127.0.0.1:" + port + "/upload/x.pdf\","
                    + "\"blob_url\":\"https://cdn/ai/uploads/x.pdf\",\"container\":\"static\",\"blob_name\":\"ai/uploads/x.pdf\"}");
        });
        server.createContext("/upload/", exchange -> {
            uploadRequest.set(capture(exchange));
            exchange.sendResponseHeaders(uploadStatus, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void aPdfRequestsTheMultimodalCredentialAndUploadsItsBytes() throws IOException {
        var bytes = "%PDF-1.4 test".getBytes(StandardCharsets.UTF_8);
        var file = file("resume.pdf", bytes);

        var attachment = uploader().upload(file);

        assertEquals("GET", credentialRequest.get().method());
        assertTrue(credentialRequest.get().path().contains("content_type=application%2Fpdf"), credentialRequest.get().path());
        assertTrue(credentialRequest.get().path().contains("category=multimodal"), credentialRequest.get().path());
        assertEquals("PUT", uploadRequest.get().method());
        assertEquals("BlockBlob", uploadRequest.get().headers().get("x-ms-blob-type"));
        assertEquals("application/pdf", uploadRequest.get().headers().get("x-ms-blob-content-type"));
        assertArrayEquals(bytes, uploadRequest.get().body());
        assertEquals("https://cdn/ai/uploads/x.pdf", attachment.url);
        assertEquals("pdf", attachment.type);
        assertEquals("resume.pdf", attachment.fileName);
        assertEquals("application/pdf", attachment.contentType);
        assertEquals("static", attachment.container);
        assertEquals("ai/uploads/x.pdf", attachment.blobName);
    }

    @Test
    void anyOtherFileRequestsTheSandboxCredential() throws IOException {
        var attachment = uploader().upload(file("notes.docx", new byte[]{1, 2, 3}));

        assertTrue(credentialRequest.get().path().contains("category=sandbox"), credentialRequest.get().path());
        assertTrue(credentialRequest.get().path().contains("wordprocessingml"), credentialRequest.get().path());
        assertEquals("file", attachment.type);
    }

    @Test
    void aMissingFileIsAUsageError() {
        var error = assertThrows(HubCliError.class, () -> uploader().upload(tempDir.resolve("no-such-file.bin")));

        assertEquals(HubExitCodes.USAGE, error.exitCode);
    }

    @Test
    void aFailedUploadIsReportedAsAToolError() throws IOException {
        uploadStatus = 500;
        var file = file("resume.pdf", new byte[]{1});

        var error = assertThrows(HubCliError.class, () -> uploader().upload(file));

        assertEquals(HubExitCodes.TOOL_ERROR, error.exitCode);
    }

    private LocalAttachmentUploader uploader() {
        return new LocalAttachmentUploader("http://127.0.0.1:" + server.getAddress().getPort(), "ctk_test", false);
    }

    private Path file(String name, byte[] bytes) throws IOException {
        var path = tempDir.resolve(name);
        Files.write(path, bytes);
        return path;
    }

    private Captured capture(HttpExchange exchange) throws IOException {
        var headers = new HashMap<String, String>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) headers.put(name.toLowerCase(Locale.ROOT), values.getFirst());
        });
        var uri = exchange.getRequestURI();
        var path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        return new Captured(exchange.getRequestMethod(), path, headers, exchange.getRequestBody().readAllBytes());
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Captured(String method, String path, Map<String, String> headers, byte[] body) {
    }
}
