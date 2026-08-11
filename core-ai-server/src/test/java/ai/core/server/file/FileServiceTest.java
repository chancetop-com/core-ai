package ai.core.server.file;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.domain.FileRecord;
import core.framework.mongo.MongoCollection;
import core.framework.util.Encodings;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileServiceTest {
    private FileService service;
    private ObjectStorageService storage;
    private ObjectStorageServiceResolver resolver;

    @BeforeEach
    void setUp() {
        service = new FileService();
        service.fileRecordCollection = fileRecordCollection();
        storage = mock(ObjectStorageService.class);
        resolver = mock(ObjectStorageServiceResolver.class);
        service.storageResolver = resolver;
        when(resolver.resolve()).thenReturn(storage);
    }

    @Test
    void shareCreatesToken() {
        var record = file("file-1");
        when(service.fileRecordCollection.get("file-1")).thenReturn(Optional.of(record));
        when(service.fileRecordCollection.update(any(Bson.class), any(Bson.class))).thenReturn(1L);

        var shared = service.share("file-1", "user-1");

        assertSame(record, shared);
        assertNotNull(shared.shareToken);
        assertNotNull(shared.sharedAt);
        verify(service.fileRecordCollection).update(any(Bson.class), any(Bson.class));
    }

    @Test
    void shareReusesExistingToken() {
        var record = file("file-1");
        record.shareToken = "existing-token";
        record.sharedAt = ZonedDateTime.now();
        when(service.fileRecordCollection.get("file-1")).thenReturn(Optional.of(record));

        var shared = service.share("file-1", "user-1");

        assertEquals("existing-token", shared.shareToken);
        verify(service.fileRecordCollection, never()).update(any(Bson.class), any(Bson.class));
    }

    @Test
    void shareReturnsTokenCreatedByConcurrentRequest() {
        var record = file("file-1");
        var latest = file("file-1");
        latest.shareToken = "concurrent-token";
        latest.sharedAt = ZonedDateTime.now();
        when(service.fileRecordCollection.get("file-1")).thenReturn(Optional.of(record)).thenReturn(Optional.of(latest));
        when(service.fileRecordCollection.update(any(Bson.class), any(Bson.class))).thenReturn(0L);

        var shared = service.share("file-1", "user-1");

        assertEquals("concurrent-token", shared.shareToken);
    }

    @Test
    void shareRejectsNonOwner() {
        var record = file("file-1");
        record.userId = "user-2";
        when(service.fileRecordCollection.get("file-1")).thenReturn(Optional.of(record));

        assertThrows(ForbiddenException.class, () -> service.share("file-1", "user-1"));
        verify(service.fileRecordCollection, never()).update(any(Bson.class), any(Bson.class));
    }

    @Test
    void getSharedFindsByToken() {
        var record = file("file-1");
        when(service.fileRecordCollection.findOne(any(Bson.class))).thenReturn(Optional.of(record));

        assertSame(record, service.getShared("token"));
    }

    @Test
    void getSharedThrowsForMissingToken() {
        when(service.fileRecordCollection.findOne(any(Bson.class))).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.getShared("missing"));
    }

    @Test
    void uploadStoresContentInObjectStorageWhenConfigured() throws IOException {
        when(resolver.artifactContainer()).thenReturn("artifacts");
        var tempFile = tempFile("hello video".getBytes(StandardCharsets.UTF_8));

        var record = service.upload("user-1", "v.mp4", "video/mp4", tempFile);

        assertNull(record.data);
        assertEquals("artifacts/artifacts/" + record.id + ".mp4", record.storagePath);
        assertEquals(11L, record.size);
        verify(storage).uploadObject(eq("artifacts"), eq("artifacts/" + record.id + ".mp4"), any(Path.class), eq("video/mp4"));
        verify(service.fileRecordCollection).insert(record);
    }

    @Test
    void uploadStoresBase64WhenObjectStorageNotConfigured() throws IOException {
        when(resolver.resolve()).thenReturn(null);
        var payload = "legacy content".getBytes(StandardCharsets.UTF_8);
        var tempFile = tempFile(payload);

        var record = service.upload("user-1", "doc.txt", "text/plain", tempFile);

        assertNull(record.storagePath);
        assertArrayEquals(payload, Base64.getDecoder().decode(record.data));
        assertEquals((long) payload.length, record.size);
    }

    @Test
    void getBytesReadsFromObjectStorageWhenMigrated() {
        var record = file("file-1");
        record.storagePath = "uploads/artifacts/file-1.pdf";
        when(storage.downloadObject("uploads", "artifacts/file-1.pdf")).thenReturn(new byte[]{1, 2, 3});

        var bytes = service.getBytes(record);

        assertArrayEquals(new byte[]{1, 2, 3}, bytes);
    }

    @Test
    void getBytesDecodesLegacyBase64() {
        var record = file("file-1");
        record.data = Base64.getEncoder().encodeToString(new byte[]{4, 5, 6});

        var bytes = service.getBytes(record);

        assertArrayEquals(new byte[]{4, 5, 6}, bytes);
    }

    @Test
    void downloadUrlResolvesPresignedUrlForMigratedContent() {
        var record = file("file-1");
        record.storagePath = "uploads/artifacts/file-1.mp4";
        when(storage.generateDownloadCredential("uploads", "artifacts/file-1.mp4"))
                .thenReturn(new ObjectStorageService.DownloadCredential("https://presigned/url", "uploads", "artifacts/file-1.mp4", "exp"));

        var url = service.downloadUrl(record);

        assertEquals("https://presigned/url", url);
    }

    @Test
    void downloadUrlReturnsNullForLegacyContent() {
        assertNull(service.downloadUrl(file("file-1")));
    }

    @Test
    void deleteRemovesObjectWhenMigrated() {
        var record = file("file-1");
        record.storagePath = "uploads/artifacts/file-1.zip";
        when(service.fileRecordCollection.get("file-1")).thenReturn(Optional.of(record));

        service.delete("file-1");

        verify(storage).deleteObject("uploads", "artifacts/file-1.zip");
        verify(service.fileRecordCollection).delete("file-1");
    }

    @Test
    void uploadStoresContentHash() throws IOException, NoSuchAlgorithmException {
        when(resolver.artifactContainer()).thenReturn("artifacts");
        var payload = "hello video".getBytes(StandardCharsets.UTF_8);
        var tempFile = tempFile(payload);

        var record = service.upload("user-1", "v.mp4", "video/mp4", tempFile);

        assertEquals(md5(payload), record.contentHash);
    }

    @Test
    void uploadIfAbsentReusesExistingRecordWhenContentMatches() throws IOException {
        var existing = file("file-1");
        when(service.fileRecordCollection.findOne(any(Bson.class))).thenReturn(Optional.of(existing));
        var tempFile = tempFile("same content".getBytes(StandardCharsets.UTF_8));

        var record = service.uploadIfAbsent("user-1", "v.mp4", "video/mp4", tempFile);

        assertSame(existing, record);
        verify(service.fileRecordCollection, never()).insert(any(FileRecord.class));
        assertFalse(Files.exists(tempFile));
    }

    @Test
    void uploadIfAbsentUploadsWhenNoContentMatch() throws IOException {
        when(service.fileRecordCollection.findOne(any(Bson.class))).thenReturn(Optional.empty());
        when(resolver.artifactContainer()).thenReturn("artifacts");
        var tempFile = tempFile("new content".getBytes(StandardCharsets.UTF_8));

        var record = service.uploadIfAbsent("user-1", "v.mp4", "video/mp4", tempFile);

        assertNotNull(record.id);
        verify(service.fileRecordCollection).insert(record);
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<FileRecord> fileRecordCollection() {
        return (MongoCollection<FileRecord>) mock(MongoCollection.class);
    }

    private Path tempFile(byte[] content) throws IOException {
        var path = Files.createTempFile("file-service-test", ".tmp");
        Files.write(path, content);
        return path;
    }

    private String md5(byte[] payload) throws NoSuchAlgorithmException {
        return Encodings.hex(MessageDigest.getInstance("MD5").digest(payload));
    }

    private FileRecord file(String id) {
        var record = new FileRecord();
        record.id = id;
        record.userId = "user-1";
        record.fileName = "report.html";
        record.size = 128L;
        record.createdAt = ZonedDateTime.now();
        return record;
    }
}
