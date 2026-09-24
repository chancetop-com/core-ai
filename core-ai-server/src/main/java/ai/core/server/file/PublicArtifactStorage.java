package ai.core.server.file;

import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.domain.FileRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Decides where an artifact blob lands and how it is addressed: public artifacts go to the public artifact
 * container and are served straight from object storage, every other file stays in the private container
 * behind share links. A public upload that fails falls back to the private container, so a missing or
 * misconfigured public container never loses an artifact.
 *
 * @author stephen
 */
final class PublicArtifactStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger(PublicArtifactStorage.class);

    /** Writes the blob into the public container when asked, retrying privately when that upload fails. */
    static void write(ObjectStorageServiceResolver storageResolver, boolean publicAccess, Consumer<String> writer) {
        if (!publicAccess) {
            writer.accept(storageResolver.artifactContainer());
            return;
        }
        var container = storageResolver.publicArtifactContainer();
        try {
            writer.accept(container);
        } catch (RuntimeException e) {
            var fallback = storageResolver.artifactContainer();
            if (fallback.equals(container)) throw e;
            LOGGER.warn("public artifact upload failed, storing privately, container={}", container, e);
            writer.accept(fallback);
        }
    }

    /**
     * Direct URL of a record stored in the public container, for callers that must hand out a link an external
     * system can fetch without the platform in between; null for private records, and null as well when the
     * deployment has no public base URL configured.
     */
    static String publicUrl(ObjectStorageServiceResolver storageResolver, FileRecord record) {
        if (!isPubliclyStored(storageResolver, record)) return null;
        var storage = storageResolver.resolve();
        if (storage == null) return null;
        return storage.publicUrl(containerOf(record.storagePath), blobOf(record.storagePath));
    }

    /** Whether the record's blob already sits in the public artifact container. */
    static boolean isPubliclyStored(ObjectStorageServiceResolver storageResolver, FileRecord record) {
        if (record.storagePath == null) return false;
        return record.storagePath.startsWith(storageResolver.publicArtifactContainer() + "/");
    }

    private static String containerOf(String storagePath) {
        return storagePath.substring(0, storagePath.indexOf('/'));
    }

    private static String blobOf(String storagePath) {
        return storagePath.substring(storagePath.indexOf('/') + 1);
    }
}
