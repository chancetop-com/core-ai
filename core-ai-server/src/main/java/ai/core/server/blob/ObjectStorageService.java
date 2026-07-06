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

    /** Server-side streaming upload from a local file (snapshot capture path). */
    void uploadObject(String container, String blobName, java.nio.file.Path file);

    /** Server-side streaming download to a local file (snapshot restore path). */
    void downloadObjectToFile(String container, String blobName, java.nio.file.Path target);

    /** Delete an object; missing objects (404) are treated as success. */
    void deleteObject(String container, String blobName);

    record UploadCredential(String uploadUrl, String blobUrl, String container, String blobName, String expiresAt) {
    }
}
