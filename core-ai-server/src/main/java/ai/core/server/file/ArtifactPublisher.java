package ai.core.server.file;

import ai.core.server.domain.FileRecord;
import core.framework.util.Strings;
import core.framework.web.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Publishes files that already live in platform storage as artifacts, without a round trip through the
 * sandbox: a chat attachment (a user-uploaded video, for instance) or a file the platform stored earlier is
 * copied into the artifact container — the public one when the caller asks for a URL an external system can
 * fetch. Copies are de-duplicated by content, so publishing the same media twice reuses the first record.
 *
 * @author stephen
 */
public class ArtifactPublisher {
    private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactPublisher.class);

    private final FileService fileService;

    public ArtifactPublisher(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * Copies a blob that already lives in platform storage into a new artifact record. With {@code publicAccess}
     * the copy lands in the public container and is served straight from object storage.
     */
    public FileRecord copyToArtifact(String userId, String fileName, String contentType,
                                     String sourceContainer, String sourceBlobName, boolean publicAccess) {
        var storage = fileService.storageResolver.resolve();
        if (storage == null) throw new IllegalStateException("object storage is not configured");
        if (!isPlatformContainer(sourceContainer)) {
            throw new BadRequestException("unsupported source container: " + sourceContainer);
        }
        var metadata = storage.headObject(sourceContainer, sourceBlobName);
        var tempFile = createTempFile();
        try {
            storage.downloadObjectToFile(sourceContainer, sourceBlobName, tempFile);
            var contentHash = fileService.computeContentHash(tempFile);
            var existing = fileService.findByContentHash(userId, contentHash).orElse(null);
            if (existing != null && (!publicAccess || PublicArtifactStorage.isPubliclyStored(fileService.storageResolver, existing))) {
                LOGGER.info("artifact copy deduplicated, id={}, contentHash={}", existing.id, contentHash);
                return existing;
            }
            var name = Strings.isBlank(fileName) ? baseName(sourceBlobName) : fileName;
            var type = Strings.isBlank(contentType) ? metadata.contentType() : contentType;
            return fileService.upload(userId, name, type, tempFile, contentHash, publicAccess);
        } finally {
            deleteTempFile(tempFile);
        }
    }

    /**
     * Returns a record delivered with the requested visibility: a public request copies a private record into
     * the public container (and leaves one already there untouched); a private request keeps the record as it
     * is, because its share link is the delivery path.
     */
    public FileRecord publish(FileRecord record, boolean publicAccess) {
        if (!publicAccess || PublicArtifactStorage.isPubliclyStored(fileService.storageResolver, record)) return record;
        if (record.storagePath != null) {
            return copyToArtifact(record.userId, record.fileName, record.contentType,
                    containerOf(record.storagePath), blobOf(record.storagePath), true);
        }
        var tempFile = createTempFile();
        try {
            Files.write(tempFile, fileService.getBytes(record));
            return fileService.upload(record.userId, record.fileName, record.contentType, tempFile, true);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to stage legacy file content", e);
        } finally {
            deleteTempFile(tempFile);
        }
    }

    private boolean isPlatformContainer(String container) {
        if (Strings.isBlank(container)) return false;
        var resolver = fileService.storageResolver;
        return container.equals(resolver.multimodalContainer())
                || container.equals(resolver.sandboxContainer())
                || container.equals(resolver.artifactContainer())
                || container.equals(resolver.publicArtifactContainer());
    }

    private Path createTempFile() {
        try {
            return Files.createTempFile("artifact-", ".bin");
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create a temp file for the artifact copy", e);
        }
    }

    private void deleteTempFile(Path tempFile) {
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            LOGGER.warn("failed to delete temp file, path={}", tempFile, e);
        }
    }

    private String baseName(String blobName) {
        var index = blobName.lastIndexOf('/');
        return index >= 0 ? blobName.substring(index + 1) : blobName;
    }

    private String containerOf(String storagePath) {
        return storagePath.substring(0, storagePath.indexOf('/'));
    }

    private String blobOf(String storagePath) {
        return storagePath.substring(storagePath.indexOf('/') + 1);
    }
}
