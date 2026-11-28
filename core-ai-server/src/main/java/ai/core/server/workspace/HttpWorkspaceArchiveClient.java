package ai.core.server.workspace;

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
import java.util.LinkedHashMap;

/**
 * JDK HttpClient streaming implementation of {@link WorkspaceArchiveClient}. Copies response and
 * request payloads directly between sockets and disk with a 64 KiB buffer while computing SHA-256
 * on the fly, avoiding any in-memory archive buffering or artificial 500 MB cap.
 *
 * @author stephen
 */
public class HttpWorkspaceArchiveClient implements WorkspaceArchiveClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpWorkspaceArchiveClient.class);
    private static final int COPY_BUFFER_SIZE = 64 * 1024;
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(30);

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static long parseLongHeader(HttpResponse<?> response, String name) {
        try {
            return Long.parseLong(response.headers().firstValue(name).orElse("0"));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String readErrorBody(HttpResponse<InputStream> response) throws IOException {
        try (InputStream in = response.body()) {
            var buf = new byte[8192];
            var sb = new StringBuilder();
            int read;
            while (sb.length() < 8192) {
                read = in.read(buf);
                if (read == -1) break;
                sb.append(new String(buf, 0, read, StandardCharsets.UTF_8));
            }
            return sb.toString();
        }
    }

    private static WorkspaceException mapRuntimeError(int status, String body, String fallbackCode, String operation) {
        RuntimeErrorResponse parsed = null;
        if (body != null && !body.isBlank() && body.trim().startsWith("{")) {
            try {
                parsed = JSON.fromJSON(RuntimeErrorResponse.class, body);
            } catch (RuntimeException ignored) {
                parsed = null;
            }
        }
        var code = parsed != null && parsed.errorCode != null && !parsed.errorCode.isBlank()
                ? parsed.errorCode
                : fallbackCode;
        var detail = parsed != null && parsed.error != null && !parsed.error.isBlank()
                ? parsed.error
                : "runtime " + operation + " failed with HTTP " + status;
        return new WorkspaceException(code, detail);
    }

    private final HttpClient httpClient;

    public HttpWorkspaceArchiveClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public QuiescentStatus probeQuiescent(String host, int port) {
        var uri = URI.create("http://" + host + ":" + port + "/workspace/stat");
        try {
            var request = HttpRequest.newBuilder(uri)
                    .timeout(PROBE_TIMEOUT)
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new QuiescentStatus(0, 0);
            }
            var stat = JSON.fromJSON(RuntimeStatResponse.class, response.body());
            return new QuiescentStatus(
                    stat.activeAsyncTasks == null ? 0 : stat.activeAsyncTasks,
                    stat.activeTerminals == null ? 0 : stat.activeTerminals);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("failed to probe workspace runtime quiescent state at {}: {}", uri, e.getMessage());
            return new QuiescentStatus(0, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkspaceException(WorkspaceException.ARCHIVE_FAILED, "interrupted while probing runtime", e);
        }
    }

    @Override
    public CaptureResult captureToFile(String host, int port, CaptureRequest captureRequest, Path targetFile) {
        var uri = URI.create("http://" + host + ":" + port + "/workspace/archive");
        var payload = new LinkedHashMap<String, Object>();
        payload.put("archiveId", captureRequest.archiveId());
        payload.put("workspaceId", captureRequest.workspaceId());
        payload.put("generation", captureRequest.generation());
        if (captureRequest.image() != null) {
            payload.put("image", captureRequest.image());
        }
        try {
            var request = HttpRequest.newBuilder(uri)
                    .timeout(TRANSFER_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSON(payload)))
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                var body = readErrorBody(response);
                throw mapRuntimeError(response.statusCode(), body, WorkspaceException.ARCHIVE_FAILED, "archive");
            }
            var digest = sha256();
            long sizeBytes = streamResponseToFile(response, targetFile, digest);
            long fileCount = parseLongHeader(response, "X-Archive-File-Count");
            var hash = HexFormat.of().formatHex(digest.digest());
            LOGGER.info("captured workspace archive from runtime: host={}, archiveId={}, files={}, size={}",
                    host, captureRequest.archiveId(), fileCount, sizeBytes);
            return new CaptureResult(hash, sizeBytes, fileCount);
        } catch (WorkspaceException e) {
            throw e;
        } catch (IOException e) {
            throw new WorkspaceException(WorkspaceException.ARCHIVE_FAILED,
                    "failed to stream archive from runtime " + uri + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkspaceException(WorkspaceException.ARCHIVE_FAILED, "interrupted while streaming archive", e);
        }
    }

    @Override
    public RestoreResult restoreFromFile(String host, int port, Path archiveFile, RestoreOptions options) {
        var uri = URI.create("http://" + host + ":" + port + "/workspace/restore");
        try {
            var builder = HttpRequest.newBuilder(uri)
                    .timeout(TRANSFER_TIMEOUT)
                    .header("Content-Type", "application/gzip")
                    .header("X-Archive-Generation", String.valueOf(options.expectedGeneration()));
            if (options.expectedWorkspaceId() != null && !options.expectedWorkspaceId().isBlank()) {
                builder.header("X-Workspace-Id", options.expectedWorkspaceId());
            }
            if (options.allowOverwrite()) {
                builder.header("X-Allow-Overwrite", "true");
            }
            var request = builder.POST(HttpRequest.BodyPublishers.ofFile(archiveFile)).build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw mapRuntimeError(response.statusCode(), response.body(), WorkspaceException.ARCHIVE_FAILED, "restore");
            }
            var parsed = JSON.fromJSON(RuntimeRestoreResponse.class, response.body());
            return new RestoreResult(
                    parsed.fileCount == null ? 0L : parsed.fileCount,
                    parsed.bytesRestored == null ? 0L : parsed.bytesRestored);
        } catch (WorkspaceException e) {
            throw e;
        } catch (IOException e) {
            throw new WorkspaceException(WorkspaceException.ARCHIVE_FAILED,
                    "failed to stream archive restore to runtime " + uri + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkspaceException(WorkspaceException.ARCHIVE_FAILED, "interrupted while streaming restore", e);
        }
    }

    private long streamResponseToFile(HttpResponse<InputStream> response, Path targetFile, MessageDigest digest) throws IOException {
        long total = 0L;
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(targetFile)) {
            var buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while (true) {
                read = in.read(buffer);
                if (read == -1) break;
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
                total += read;
            }
        }
        return total;
    }

    public static class RuntimeStatResponse {
        @Property(name = "active_async_tasks")
        public Integer activeAsyncTasks;

        @Property(name = "active_terminals")
        public Integer activeTerminals;
    }

    public static class RuntimeErrorResponse {
        @Property(name = "status")
        public String status;

        @Property(name = "errorCode")
        public String errorCode;

        @Property(name = "error")
        public String error;
    }

    public static class RuntimeRestoreResponse {
        @Property(name = "status")
        public String status;

        @Property(name = "fileCount")
        public Long fileCount;

        @Property(name = "bytesRestored")
        public Long bytesRestored;
    }
}
