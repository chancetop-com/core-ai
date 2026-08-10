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
 * Azure Blob Storage implementation of {@link ObjectStorageService}.
 * Delegates SAS token generation to {@link AzureBlobSasService}.
 *
 * @author stephen
 */
public class AzureObjectStorageService implements ObjectStorageService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AzureObjectStorageService.class);
    private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(10);

    // best-effort cleanup of a partially written download; must not mask the original failure
    private static void deletePartialFileQuietly(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            LOGGER.warn("failed to delete partial download file: {}", target, e);
        }
    }

    private final AzureBlobSasService sasService;
    private final String publicBaseUrl;
    private final String cdnBaseUrl;
    private final HttpClient httpClient;

    public AzureObjectStorageService(AzureBlobSasService sasService, String publicBaseUrl) {
        this(sasService, publicBaseUrl, null);
    }

    public AzureObjectStorageService(AzureBlobSasService sasService, String publicBaseUrl, String cdnBaseUrl) {
        this.sasService = sasService;
        this.publicBaseUrl = publicBaseUrl;
        this.cdnBaseUrl = cdnBaseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public UploadCredential generateUploadCredential(String container, String blobName) {
        var result = sasService.generateContainerSas(container, blobName, 10);
        var blobUrl = cdnBaseUrl != null && !cdnBaseUrl.isBlank()
                ? cdnBaseUrl + "/" + result.blobName()
                : publicBaseUrl != null ? publicBaseUrl + "/" + result.container() + "/" + result.blobName() : result.blobUrl();
        return new UploadCredential(result.uploadUrl(), blobUrl, result.container(), result.blobName(), result.expiresAt());
    }

    @Override
    public DownloadCredential generateDownloadCredential(String container, String blobName) {
        var result = sasService.generateReadBlobSas(container, blobName, 60);
        return new DownloadCredential(result.uploadUrl(), result.container(), result.blobName(), result.expiresAt());
    }

    @Override
    public byte[] downloadObject(String container, String blobName) {
        var readSas = sasService.generateReadBlobSas(container, blobName, 5);
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(readSas.uploadUrl()))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new RuntimeException("download failed: status=" + response.statusCode() + ", container=" + container + ", blob=" + blobName);
            }
            LOGGER.info("downloaded blob: container={}, blob={}, size={}", container, blobName, response.body().length);
            return response.body();
        } catch (IOException e) {
            throw new RuntimeException("failed to download blob: container=" + container + ", blob=" + blobName, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while downloading blob", e);
        }
    }

    @Override
    public void uploadObject(String container, String blobName, Path file) {
        uploadObject(container, blobName, file, null);
    }

    @Override
    public void uploadObject(String container, String blobName, Path file, String contentType) {
        var sas = sasService.generateContainerSas(container, blobName, 30);
        try {
            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(sas.uploadUrl()))
                    .timeout(TRANSFER_TIMEOUT)
                    .header("x-ms-blob-type", "BlockBlob")
                    .PUT(HttpRequest.BodyPublishers.ofFile(file));
            if (contentType != null && !contentType.isBlank()) {
                builder.header("x-ms-blob-content-type", contentType);
            }
            var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201) {
                throw new RuntimeException("upload failed: status=" + response.statusCode() + ", body=" + response.body());
            }
            LOGGER.info("uploaded blob: container={}, blob={}, size={}", container, blobName, Files.size(file));
        } catch (IOException e) {
            throw new RuntimeException("failed to upload blob: container=" + container + ", blob=" + blobName, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while uploading blob", e);
        }
    }

    @Override
    public void downloadObjectToFile(String container, String blobName, Path target) {
        var readSas = sasService.generateReadBlobSas(container, blobName, 10);
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(readSas.uploadUrl()))
                    .timeout(TRANSFER_TIMEOUT)
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(target));
            if (response.statusCode() != 200) {
                throw new RuntimeException("download failed: status=" + response.statusCode() + ", container=" + container + ", blob=" + blobName);
            }
        } catch (IOException e) {
            deletePartialFileQuietly(target);
            throw new RuntimeException("failed to download blob: container=" + container + ", blob=" + blobName, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deletePartialFileQuietly(target);
            throw new RuntimeException("interrupted while downloading blob", e);
        }
        LOGGER.info("downloaded blob to file: container={}, blob={}, size={}", container, blobName, target.toFile().length());
    }

    @Override
    public ObjectMetadata headObject(String container, String blobName) {
        var readSas = sasService.generateReadBlobSas(container, blobName, 5);
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(readSas.uploadUrl()))
                    .timeout(Duration.ofSeconds(30))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                throw new RuntimeException("head failed: status=" + response.statusCode() + ", container=" + container + ", blob=" + blobName);
            }
            return new ObjectMetadata(
                    response.headers().firstValueAsLong("Content-Length").orElse(0L),
                    response.headers().firstValue("ETag").orElse(null),
                    response.headers().firstValue("Content-Type").orElse(null),
                    response.headers().firstValue("Last-Modified").orElse(null));
        } catch (IOException e) {
            throw new RuntimeException("failed to inspect blob: container=" + container + ", blob=" + blobName, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while inspecting blob", e);
        }
    }

    @Override
    public boolean exists(String container, String blobName) {
        var readSas = sasService.generateReadBlobSas(container, blobName, 5);
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(readSas.uploadUrl()))
                    .timeout(Duration.ofSeconds(30))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 200) return true;
            if (response.statusCode() == 404) return false;
            throw new RuntimeException("head failed: status=" + response.statusCode() + ", container=" + container + ", blob=" + blobName);
        } catch (IOException e) {
            throw new RuntimeException("failed to inspect blob: container=" + container + ", blob=" + blobName, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while inspecting blob", e);
        }
    }

    @Override
    public void deleteObject(String container, String blobName) {
        var sas = sasService.generateDeleteBlobSas(container, blobName, 5);
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(sas.uploadUrl()))
                    .timeout(Duration.ofSeconds(30))
                    .DELETE()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 202 && response.statusCode() != 404) {
                throw new RuntimeException("delete failed: status=" + response.statusCode() + ", body=" + response.body());
            }
            LOGGER.info("deleted blob: container={}, blob={}, status={}", container, blobName, response.statusCode());
        } catch (IOException e) {
            throw new RuntimeException("failed to delete blob: container=" + container + ", blob=" + blobName, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while deleting blob", e);
        }
    }
}
