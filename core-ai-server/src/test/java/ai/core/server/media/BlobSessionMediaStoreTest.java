package ai.core.server.media;

import ai.core.media.MediaRef;
import ai.core.media.SessionMediaKey;
import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.domain.SessionAttachmentRef;
import ai.core.server.domain.SessionAttachmentRefRepository;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author core-ai
 */
class BlobSessionMediaStoreTest {
    private static final SessionMediaKey KEY = new SessionMediaKey("session-1", "user-1");

    private final FakeStorage storage = new FakeStorage();
    private final FakeRepository repository = new FakeRepository();
    private final BlobSessionMediaStore store = new BlobSessionMediaStore(new FakeResolver(storage), repository);

    @Test
    void uploadsIndexesAndReadsAnObject() {
        var ref = store.put(KEY, new byte[]{1, 2, 3}, "image/png", "photo.png", "attachment");

        assertEquals(SessionAttachmentRef.KIND_MEDIA, repository.media.getFirst().kind);
        assertTrue(repository.media.getFirst().blobName.startsWith(BlobSessionMediaStore.BLOB_PREFIX));
        assertArrayEquals(new byte[]{1, 2, 3}, store.read(KEY, ref).orElseThrow());
        assertEquals("photo.png", store.find(KEY, ref.id).orElseThrow().displayName);
    }

    @Test
    void registersAnUploadBlobOnlyOnce() {
        var upload = MediaRef.blob(null, "multimodal", "ai/uploads/photo.png", "image/png", "png", 3L)
                .etag("etag-1")
                .displayName("photo.png")
                .source("upload");

        var first = store.register(KEY, upload);
        var second = store.register(KEY, upload);

        assertEquals(first.id, second.id);
        assertEquals(1, repository.media.size());
        assertEquals("image/png", first.mediaType);
    }

    @Test
    void refusesToReadAnotherUsersObject() {
        var ref = store.put(KEY, new byte[]{1}, "image/png", "photo.png", "attachment");

        assertTrue(store.read(new SessionMediaKey("session-1", "user-2"), ref).isEmpty());
    }

    @Test
    void treatsAnObjectPastTheTtlAsMissingAndDeletesIt() {
        var ref = store.put(KEY, new byte[]{1}, "image/png", "photo.png", "attachment");
        repository.media.getFirst().createdAt = ZonedDateTime.now().minusHours(25);

        assertTrue(store.read(KEY, ref).isEmpty());
        assertTrue(repository.media.isEmpty());
        assertTrue(storage.objects.isEmpty());
    }

    private static final class FakeResolver extends ObjectStorageServiceResolver {
        private final ObjectStorageService storage;

        FakeResolver(ObjectStorageService storage) {
            this.storage = storage;
        }

        @Override
        public ObjectStorageService resolve() {
            return storage;
        }

        @Override
        public String multimodalContainer() {
            return "multimodal";
        }
    }

    private static final class FakeStorage implements ObjectStorageService {
        private final Map<String, byte[]> objects = new LinkedHashMap<>();

        @Override
        public UploadCredential generateUploadCredential(String container, String blobName) {
            return null;
        }

        @Override
        public DownloadCredential generateDownloadCredential(String container, String blobName) {
            return null;
        }

        @Override
        public byte[] downloadObject(String container, String blobName) {
            return objects.get(blobName);
        }

        @Override
        public void uploadObject(String container, String blobName, Path file) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void uploadObject(String container, String blobName, byte[] data, String contentType) {
            objects.put(blobName, data);
        }

        @Override
        public void downloadObjectToFile(String container, String blobName, Path target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ObjectMetadata headObject(String container, String blobName) {
            var data = objects.get(blobName);
            return data == null ? null : new ObjectMetadata((long) data.length, "etag-" + blobName, null, null);
        }

        @Override
        public boolean exists(String container, String blobName) {
            return objects.containsKey(blobName);
        }

        @Override
        public void deleteObject(String container, String blobName) {
            objects.remove(blobName);
        }
    }

    private static final class FakeRepository implements SessionAttachmentRefRepository {
        private final List<SessionAttachmentRef> media = new ArrayList<>();

        @Override
        public void insert(SessionAttachmentRef reference) {
            media.add(reference);
        }

        @Override
        public boolean insertIfAbsent(SessionAttachmentRef reference) {
            return media.add(reference);
        }

        @Override
        public SessionAttachmentRef findOwned(String referenceId, String sessionId, String userId) {
            return media.stream()
                    .filter(reference -> reference.id.equals(referenceId)
                            && reference.sessionId.equals(sessionId)
                            && reference.userId.equals(userId))
                    .findFirst().orElse(null);
        }

        @Override
        public List<SessionAttachmentRef> findOwnedByPrefix(String prefix, String sessionId, String userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SessionAttachmentRef> findOwnedVideos(String sessionId, String userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void attachFile(String referenceId, String fileId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SessionAttachmentRef findBySource(String sessionId, String userId, String container, String blobName) {
            return media.stream()
                    .filter(reference -> reference.sessionId.equals(sessionId)
                            && reference.userId.equals(userId)
                            && reference.container.equals(container)
                            && reference.blobName.equals(blobName))
                    .findFirst().orElse(null);
        }

        @Override
        public List<SessionAttachmentRef> findSandboxAttachments(String sessionId, String userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SessionAttachmentRef> findOwnedMedia(String sessionId, String userId) {
            return media.stream()
                    .filter(reference -> reference.sessionId.equals(sessionId) && reference.userId.equals(userId))
                    .toList();
        }

        @Override
        public List<SessionAttachmentRef> findExpiredMedia(ZonedDateTime cutoff, int limit) {
            return media.stream()
                    .filter(reference -> reference.createdAt != null && reference.createdAt.isBefore(cutoff))
                    .limit(limit)
                    .toList();
        }

        @Override
        public void delete(String referenceId) {
            media.removeIf(reference -> reference.id.equals(referenceId));
        }
    }
}
