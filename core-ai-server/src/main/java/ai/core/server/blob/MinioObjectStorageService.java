package ai.core.server.blob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * MinIO / S3-compatible implementation of {@link ObjectStorageService}.
 * Uses AWS Signature V4 pre-signed URLs for upload and download.
 *
 * @author stephen
 */
public class MinioObjectStorageService implements ObjectStorageService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinioObjectStorageService.class);

    private final MinioPresigner presigner;
    private final String publicBaseUrl;
    private final HttpClient httpClient;

    public MinioObjectStorageService(String endpoint, String region, String accessKey, String secretKey, String publicBaseUrl) {
        this.presigner = new MinioPresigner(endpoint, region, accessKey, secretKey);
        this.publicBaseUrl = stripTrailingSlash(publicBaseUrl);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public UploadCredential generateUploadCredential(String container, String blobName) {
        var result = presigner.presignedPutUrl(container, blobName, 600); // 10 min
        var blobUrl = publicBaseUrl != null && !publicBaseUrl.isBlank()
                ? publicBaseUrl + "/" + container + "/" + blobName
                : result.rawUrl();
        return new UploadCredential(result.presignedUrl(), blobUrl, result.bucket(), result.key(), result.timestamp());
    }

    @Override
    public byte[] downloadObject(String container, String blobName) {
        var result = presigner.presignedGetUrl(container, blobName, 300); // 5 min
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(result.presignedUrl()))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("download failed: status=" + response.statusCode() + ", bucket=" + container + ", key=" + blobName);
            }
            LOGGER.info("downloaded object: bucket={}, key={}, size={}", container, blobName, response.body().length);
            return response.body();
        } catch (IOException e) {
            throw new RuntimeException("failed to download object: bucket=" + container + ", key=" + blobName, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while downloading object", e);
        }
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return null;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(10);

    @Override
    public void uploadObject(String container, String blobName, Path file) {
        var result = presigner.presignedPutUrl(container, blobName, 1800);
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(result.presignedUrl()))
                    .timeout(TRANSFER_TIMEOUT)
                    .PUT(HttpRequest.BodyPublishers.ofFile(file))
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("upload failed: status=" + response.statusCode() + ", body=" + response.body());
            }
            LOGGER.info("uploaded object: bucket={}, key={}, size={}", container, blobName, Files.size(file));
        } catch (IOException e) {
            throw new RuntimeException("failed to upload object: bucket=" + container + ", key=" + blobName, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while uploading object", e);
        }
    }

    @Override
    public void downloadObjectToFile(String container, String blobName, Path target) {
        var result = presigner.presignedGetUrl(container, blobName, 600);
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(result.presignedUrl()))
                    .timeout(TRANSFER_TIMEOUT)
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(target));
            if (response.statusCode() != 200) {
                throw new IOException("download failed: status=" + response.statusCode() + ", bucket=" + container + ", key=" + blobName);
            }
        } catch (IOException e) {
            // clean up the partial file on any failure path, including mid-stream errors
            deletePartialFileQuietly(target);
            throw new RuntimeException("failed to download object: bucket=" + container + ", key=" + blobName, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deletePartialFileQuietly(target);
            throw new RuntimeException("interrupted while downloading object", e);
        }
    }

    // best-effort cleanup of a partially written download; must not mask the original failure
    private static void deletePartialFileQuietly(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            LOGGER.warn("failed to delete partial download file: {}", target, e);
        }
    }

    @Override
    public void deleteObject(String container, String blobName) {
        var result = presigner.presignedDeleteUrl(container, blobName, 300);
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(result.presignedUrl()))
                    .timeout(Duration.ofSeconds(30))
                    .DELETE()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 204 && response.statusCode() != 200 && response.statusCode() != 404) {
                throw new IOException("delete failed: status=" + response.statusCode() + ", body=" + response.body());
            }
        } catch (IOException e) {
            throw new RuntimeException("failed to delete object: bucket=" + container + ", key=" + blobName, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while deleting object", e);
        }
    }
}
