package ai.core.server.media;

import ai.core.media.MediaHash;
import ai.core.media.MediaLimitExceededException;
import ai.core.media.MediaRef;
import ai.core.media.MediaRefKind;
import ai.core.media.MediaStoreLimits;
import ai.core.media.MediaStoreStats;
import ai.core.media.SessionMediaKey;
import ai.core.media.SessionMediaStore;
import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.domain.SessionAttachmentRef;
import ai.core.server.domain.SessionAttachmentRefRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server media store: bytes live in the multimodal object-storage container, the index is the same
 * {@code session_attachment_ref} collection used by uploads ({@code kind=MEDIA}). Owners are checked on
 * every read; a session may keep at most {@link MediaStoreLimits#MAX_SESSION_BYTES} and every object
 * expires after {@link MediaStoreLimits#TTL}.
 *
 * @author core-ai
 */
public class BlobSessionMediaStore implements SessionMediaStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(BlobSessionMediaStore.class);
    static final String BLOB_PREFIX = "ai/session-media/";

    private static ObjectStorageService.ObjectMetadata head(ObjectStorageService storage, String container, String blobName) {
        try {
            return storage.headObject(container, blobName);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static MediaRef toRef(SessionAttachmentRef reference) {
        return MediaRef.blob(reference.id, reference.container, reference.blobName, reference.contentType,
                        format(reference.contentType), reference.sourceSizeBytes)
                .etag(reference.sourceETag)
                .displayName(reference.fileName)
                .source("session media");
    }

    static String blobName(String sessionId, String sha256, String extension) {
        return BLOB_PREFIX + sessionId + "/" + sha256 + "." + extension;
    }

    static String extension(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) return "bin";
        var normalized = mediaType.toLowerCase(java.util.Locale.ROOT);
        if ("image/jpeg".equals(normalized)) return "jpg";
        var slash = normalized.indexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    static String format(String mediaType) {
        var extension = extension(mediaType);
        return "jpg".equals(extension) ? "jpeg" : extension;
    }

    private final ObjectStorageServiceResolver storageResolver;
    private final SessionAttachmentRefRepository repository;
    private final AtomicLong missed = new AtomicLong();
    private final AtomicLong evicted = new AtomicLong();

    public BlobSessionMediaStore(ObjectStorageServiceResolver storageResolver, SessionAttachmentRefRepository repository) {
        this.storageResolver = storageResolver;
        this.repository = repository;
    }

    @Override
    public MediaRef put(SessionMediaKey key, byte[] bytes, String mediaType, String displayName, String source) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("media bytes are required");
        if (bytes.length > MediaStoreLimits.MAX_IMAGE_BYTES) {
            throw new MediaLimitExceededException("image exceeds the per-object budget", MediaStoreLimits.MAX_IMAGE_BYTES, bytes.length);
        }
        var storage = requireStorage();
        var container = container();
        var sha256 = MediaHash.sha256(bytes);
        var blobName = blobName(key.sessionId(), sha256, extension(mediaType));
        var current = sessionBytes(key);
        if (current + bytes.length > MediaStoreLimits.MAX_SESSION_BYTES) {
            throw new MediaLimitExceededException("session media store exceeds its budget", MediaStoreLimits.MAX_SESSION_BYTES, current + bytes.length);
        }
        var existing = repository.findBySource(key.sessionId(), key.userId(), container, blobName);
        if (existing != null) return toRef(existing);
        storage.uploadObject(container, blobName, bytes, mediaType);
        var metadata = head(storage, container, blobName);
        var reference = new SessionAttachmentRef();
        reference.id = "media_" + UUID.randomUUID();
        reference.sessionId = key.sessionId();
        reference.userId = key.userId();
        reference.container = container;
        reference.blobName = blobName;
        reference.fileName = displayName;
        reference.kind = SessionAttachmentRef.KIND_MEDIA;
        reference.contentType = mediaType;
        reference.sourceSizeBytes = (long) bytes.length;
        reference.sourceETag = metadata == null ? null : metadata.etag();
        reference.createdAt = ZonedDateTime.now();
        repository.insert(reference);
        return MediaRef.blob(reference.id, container, blobName, mediaType, format(mediaType), (long) bytes.length)
                .sha256(sha256)
                .etag(reference.sourceETag)
                .displayName(displayName)
                .source(source);
    }

    @Override
    public MediaRef register(SessionMediaKey key, MediaRef existing) {
        if (existing == null) throw new IllegalArgumentException("media ref is required");
        existing.validate();
        if (existing.kind != MediaRefKind.BLOB) throw new IllegalArgumentException("server media store only registers blob refs");
        var container = container();
        if (!container.equals(existing.container) || !existing.blobName.startsWith("ai/")) {
            throw new IllegalArgumentException("media ref does not belong to the multimodal container");
        }
        var reference = repository.findBySource(key.sessionId(), key.userId(), existing.container, existing.blobName);
        if (reference == null) {
            reference = new SessionAttachmentRef();
            reference.id = existing.id != null && existing.id.startsWith("media_") ? existing.id : "media_" + UUID.randomUUID();
            reference.sessionId = key.sessionId();
            reference.userId = key.userId();
            reference.container = existing.container;
            reference.blobName = existing.blobName;
            reference.fileName = existing.displayName;
            reference.kind = SessionAttachmentRef.KIND_MEDIA;
            reference.contentType = existing.mediaType;
            reference.sourceSizeBytes = existing.sizeBytes;
            reference.sourceETag = existing.etag;
            reference.createdAt = ZonedDateTime.now();
            repository.insert(reference);
        }
        return toRef(reference);
    }

    @Override
    public Optional<MediaRef> find(SessionMediaKey key, String mediaId) {
        if (mediaId == null) return Optional.empty();
        var reference = repository.findOwned(mediaId, key.sessionId(), key.userId());
        if (reference == null || !SessionAttachmentRef.KIND_MEDIA.equals(reference.kind)) return Optional.empty();
        return Optional.of(toRef(reference));
    }

    @Override
    public Optional<byte[]> read(SessionMediaKey key, MediaRef ref) {
        if (ref == null || ref.id == null) return Optional.empty();
        var reference = repository.findOwned(ref.id, key.sessionId(), key.userId());
        if (reference == null || !SessionAttachmentRef.KIND_MEDIA.equals(reference.kind)) {
            missed.incrementAndGet();
            return Optional.empty();
        }
        if (!reference.container.equals(ref.container) || !reference.blobName.equals(ref.blobName)) {
            missed.incrementAndGet();
            return Optional.empty();
        }
        if (expired(reference)) {
            deleteObject(reference);
            missed.incrementAndGet();
            return Optional.empty();
        }
        try {
            var storage = requireStorage();
            var metadata = head(storage, reference.container, reference.blobName);
            if (metadata != null && metadata.sizeBytes() != null && metadata.sizeBytes() > MediaStoreLimits.MAX_IMAGE_BYTES) {
                missed.incrementAndGet();
                return Optional.empty();
            }
            return Optional.of(storage.downloadObject(reference.container, reference.blobName));
        } catch (RuntimeException e) {
            LOGGER.warn("failed to read a session media object: id={}, error={}", reference.id, e.getMessage());
            missed.incrementAndGet();
            return Optional.empty();
        }
    }

    @Override
    public void deleteSession(String sessionId) {
        // the caller only knows the session, not the user; both ownership columns still guard the read path
        LOGGER.debug("deleteSession is handled per object by the maintenance sweep: sessionId={}", sessionId);
    }

    @Override
    public MediaStoreStats stats() {
        return new MediaStoreStats(0, 0, missed.get(), evicted.get());
    }

    /** Deletes one expired object together with its index row. */
    public boolean deleteExpired(SessionAttachmentRef reference) {
        if (!expired(reference)) return false;
        deleteObject(reference);
        return true;
    }

    private void deleteObject(SessionAttachmentRef reference) {
        try {
            var storage = requireStorage();
            storage.deleteObject(reference.container, reference.blobName);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to delete a session media object: id={}, error={}", reference.id, e.getMessage());
        }
        repository.delete(reference.id);
        evicted.addAndGet(reference.sourceSizeBytes == null ? 0 : reference.sourceSizeBytes);
    }

    private long sessionBytes(SessionMediaKey key) {
        long total = 0;
        List<SessionAttachmentRef> objects = repository.findOwnedMedia(key.sessionId(), key.userId());
        for (var object : objects) {
            if (expired(object)) continue;
            total += object.sourceSizeBytes == null ? 0 : object.sourceSizeBytes;
        }
        return total;
    }

    private boolean expired(SessionAttachmentRef reference) {
        return reference.createdAt != null
                && reference.createdAt.plus(MediaStoreLimits.TTL).isBefore(ZonedDateTime.now());
    }

    private String container() {
        var container = storageResolver.multimodalContainer();
        if (container == null) throw new IllegalStateException("multimodal object storage is not configured");
        return container;
    }

    private ObjectStorageService requireStorage() {
        var storage = storageResolver.resolve();
        if (storage == null) throw new IllegalStateException("object storage is not configured");
        return storage;
    }
}
