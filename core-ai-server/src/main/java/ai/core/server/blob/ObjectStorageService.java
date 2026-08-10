package ai.core.server.blob;

/**
 * Provider-agnostic object storage abstraction for generating pre-signed upload credentials
 * and downloading stored objects.
 * <p>
 * Implementations handle provider-specific credential generation (SAS for Azure,
 * pre-signed URL for S3/MinIO, etc.). Switch providers by implementing this interface
 * and updating the binding in {@code ObjectStorageModule}.
 *
 * @author stephen
 */
public interface ObjectStorageService {

    UploadCredential generateUploadCredential(String container, String blobName);

    /** Pre-signed read URL for direct browser downloads; the storage backend handles Range/ETag natively. */
    DownloadCredential generateDownloadCredential(String container, String blobName);

    byte[] downloadObject(String container, String blobName);

    /** Server-side streaming upload from a local file (snapshot capture path). */
    void uploadObject(String container, String blobName, java.nio.file.Path file);

    /** Upload with an explicit content type so direct downloads carry the right Content-Type header. */
    default void uploadObject(String container, String blobName, java.nio.file.Path file, String contentType) {
        uploadObject(container, blobName, file);
    }

    /** Server-side streaming download to a local file (snapshot restore path). */
    void downloadObjectToFile(String container, String blobName, java.nio.file.Path target);

    ObjectMetadata headObject(String container, String blobName);

    /** Whether the object exists; 404 (missing) returns false, other errors throw. */
    boolean exists(String container, String blobName);

    /** Delete an object; missing objects (404) are treated as success. */
    void deleteObject(String container, String blobName);

    record ObjectMetadata(Long sizeBytes, String etag, String contentType, String lastModified) {
    }

    record UploadCredential(String uploadUrl, String blobUrl, String container, String blobName, String expiresAt) {
    }

    record DownloadCredential(String downloadUrl, String container, String blobName, String expiresAt) {
    }
}
