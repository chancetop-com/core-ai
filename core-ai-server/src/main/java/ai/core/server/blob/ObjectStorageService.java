package ai.core.server.blob;

/**
 * Provider-agnostic object storage abstraction for generating pre-signed upload credentials
 * and downloading stored objects.
 * <p>
 * Implementations handle provider-specific credential generation (SAS for Azure,
 * pre-signed URL for S3/MinIO, etc.). Switch providers by implementing this interface
 * and updating the binding in {@code ServerModule}.
 *
 * @author stephen
 */
public interface ObjectStorageService {

    UploadCredential generateUploadCredential(String container, String blobName);

    byte[] downloadObject(String container, String blobName);

    record UploadCredential(String uploadUrl, String blobUrl, String container, String blobName, String expiresAt) {
    }
}
