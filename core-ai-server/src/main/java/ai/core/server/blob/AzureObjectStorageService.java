package ai.core.server.blob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Azure Blob Storage implementation of {@link ObjectStorageService}.
 * Delegates SAS token generation to {@link AzureBlobSasService}.
 *
 * @author stephen
 */
public class AzureObjectStorageService implements ObjectStorageService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AzureObjectStorageService.class);

    private final AzureBlobSasService sasService;
    private final String publicBaseUrl;
    private final HttpClient httpClient;

    public AzureObjectStorageService(AzureBlobSasService sasService, String publicBaseUrl) {
        this.sasService = sasService;
        this.publicBaseUrl = publicBaseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public UploadCredential generateUploadCredential(String container, String blobName) {
        var result = sasService.generateContainerSas(container, blobName, 10);
        var blobUrl = publicBaseUrl != null ? publicBaseUrl + "/" + result.container() + "/" + result.blobName() : result.blobUrl();
        return new UploadCredential(result.uploadUrl(), blobUrl, result.container(), result.blobName(), result.expiresAt());
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
                throw new IOException("download failed: status=" + response.statusCode() + ", container=" + container + ", blob=" + blobName);
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
}
