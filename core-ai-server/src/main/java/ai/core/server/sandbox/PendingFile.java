package ai.core.server.sandbox;

/** A file queued for upload into a session's sandbox once it materializes: either a blob-storage object
 *  landing at {@code /tmp/<fileName>} (chat uploads), or a staged FileRecord landing at {@code targetPath}
 *  (workflow artifact staging — see {@link SandboxService#addStagedFile}). */
public record PendingFile(String fileName, String container, String blobName, String fileId, String targetPath) {
    public PendingFile(String fileName, String container, String blobName) {
        this(fileName, container, blobName, null, null);
    }
}
