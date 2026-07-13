package ai.core.server.sandbox.snapshot;

import core.framework.api.json.Property;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * HTTP client for the sandbox runtime's snapshot endpoints. Uses the JDK
 * HttpClient with file-streaming bodies so multi-hundred-MB archives never
 * live on the server heap.
 *
 * @author xander
 */
public class SandboxSnapshotClient {
    static final long MAX_ARCHIVE_BYTES = 500L << 20;
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxSnapshotClient.class);
    private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(5);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public CaptureResult capture(String ip, int port, Path targetFile) {
        var uri = URI.create("http://" + ip + ":" + port + "/snapshot");
        try {
            var request = HttpRequest.newBuilder(uri)
                    .timeout(TRANSFER_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new RuntimeException("snapshot capture failed: status=" + response.statusCode() + ", body=" + readErrorBody(response));
            }
            var digest = sha256();
            long size = readResponseBody(response, targetFile, digest);
            var fileCount = parseIntHeader(response, "X-Snapshot-File-Count");
            var runtimeVersion = response.headers().firstValue("X-Snapshot-Runtime-Version").orElse("unknown");
            var sha = HexFormat.of().formatHex(digest.digest());
            LOGGER.info("snapshot captured from runtime: ip={}, files={}, size={}", ip, fileCount, size);
            return new CaptureResult(sha, size, fileCount, runtimeVersion);
        } catch (IOException e) {
            throw new RuntimeException("failed to capture snapshot from " + uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while capturing snapshot", e);
        }
    }

    private String readErrorBody(HttpResponse<InputStream> response) throws IOException {
        try (InputStream in = response.body()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private long readResponseBody(HttpResponse<InputStream> response, Path targetFile, MessageDigest digest) throws IOException {
        long size = 0;
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(targetFile)) {
            var buffer = new byte[64 * 1024];
            int read;
            while (true) {
                read = in.read(buffer);
                if (read == -1) break;
                size += read;
                if (size > MAX_ARCHIVE_BYTES) {
                    throw new IOException("snapshot archive exceeds max size (" + MAX_ARCHIVE_BYTES + " bytes)");
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        return size;
    }

    public void restore(String ip, int port, Path tarFile, String sha256) {
        var uri = URI.create("http://" + ip + ":" + port + "/snapshot/restore");
        try {
            var request = HttpRequest.newBuilder(uri)
                    .timeout(TRANSFER_TIMEOUT)
                    .header("X-Snapshot-Sha256", sha256)
                    .POST(HttpRequest.BodyPublishers.ofFile(tarFile))
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("snapshot restore failed: status=" + response.statusCode() + ", body=" + response.body());
            }
            LOGGER.info("snapshot restored into runtime: ip={}, response={}", ip, response.body());
        } catch (IOException e) {
            throw new RuntimeException("failed to restore snapshot to " + uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while restoring snapshot", e);
        }
    }

    public String fetchRuntimeVersion(String ip, int port) {
        try {
            var uri = URI.create("http://" + ip + ":" + port + "/health");
            var request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return "unknown";
            var health = JSON.fromJSON(HealthResponse.class, response.body());
            return health.version != null ? health.version : "unknown";
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("failed to fetch runtime version from {}:{}: {}", ip, port, e.getMessage());
            return "unknown";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }

    private int parseIntHeader(HttpResponse<?> response, String name) {
        try {
            return Integer.parseInt(response.headers().firstValue(name).orElse("0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record CaptureResult(String sha256, long sizeBytes, int fileCount, String runtimeVersion) {
    }

    public static class HealthResponse {
        @Property(name = "status")
        public String status;
        @Property(name = "version")
        public String version;
    }
}
