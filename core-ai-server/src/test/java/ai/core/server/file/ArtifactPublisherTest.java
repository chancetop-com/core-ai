package ai.core.server.file;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.domain.FileRecord;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactPublisherTest {
    private FileService fileService;
    private ArtifactPublisher publisher;
    private ObjectStorageService storage;
    private ObjectStorageServiceResolver resolver;

    @BeforeEach
    void setUp() {
        fileService = new FileService();
        fileService.fileRecordCollection = fileRecordCollection();
        storage = mock(ObjectStorageService.class);
        resolver = mock(ObjectStorageServiceResolver.class);
        fileService.storageResolver = resolver;
        when(resolver.resolve()).thenReturn(storage);
        publisher = new ArtifactPublisher(fileService);
    }

    @Test
    void copiesPlatformBlobIntoThePublicContainer() throws IOException {
        when(resolver.multimodalContainer()).thenReturn("static");
        when(resolver.publicArtifactContainer()).thenReturn("public-artifacts");
        when(resolver.artifactContainer()).thenReturn("artifacts");
        var payload = "video bytes".getBytes(StandardCharsets.UTF_8);
        when(storage.headObject("static", "ai/uploads/x.mp4"))
                .thenReturn(new ObjectStorageService.ObjectMetadata((long) payload.length, "etag", "video/mp4", "now"));
        downloadWrites("static", "ai/uploads/x.mp4", payload);
        when(fileService.fileRecordCollection.find(any(Query.class))).thenReturn(List.of());

        var record = publisher.copyToArtifact("user-1", "promo.mp4", null, "static", "ai/uploads/x.mp4", true);

        assertEquals("public-artifacts/artifacts/" + record.id + ".mp4", record.storagePath);
        assertEquals("video/mp4", record.contentType);
        verify(storage).uploadObject(eq("public-artifacts"), eq("artifacts/" + record.id + ".mp4"), any(Path.class), eq("video/mp4"));
        verify(fileService.fileRecordCollection).insert(record);
    }

    @Test
    void reusesAPublicRecordWithTheSameContent() throws IOException {
        when(resolver.multimodalContainer()).thenReturn("static");
        when(resolver.publicArtifactContainer()).thenReturn("public-artifacts");
        when(storage.headObject("static", "ai/uploads/x.mp4"))
                .thenReturn(new ObjectStorageService.ObjectMetadata(10L, "etag", "video/mp4", "now"));
        downloadWrites("static", "ai/uploads/x.mp4", "same video".getBytes(StandardCharsets.UTF_8));
        var existing = file("file-1");
        existing.storagePath = "public-artifacts/artifacts/file-1.mp4";
        when(fileService.fileRecordCollection.find(any(Query.class))).thenReturn(List.of(existing));

        var record = publisher.copyToArtifact("user-1", "promo.mp4", "video/mp4", "static", "ai/uploads/x.mp4", true);

        assertSame(existing, record);
        verify(fileService.fileRecordCollection, never()).insert(any(FileRecord.class));
    }

    @Test
    void rejectsAContainerThePlatformDoesNotOwn() {
        when(resolver.multimodalContainer()).thenReturn("static");
        when(resolver.publicArtifactContainer()).thenReturn("public-artifacts");

        assertThrows(BadRequestException.class,
                () -> publisher.copyToArtifact("user-1", "x.mp4", "video/mp4", "someone-elses", "x.mp4", true));
    }

    @Test
    void leavesAPrivateRecordUntouched() {
        var record = file("file-1");
        record.storagePath = "artifacts/artifacts/file-1.mp4";

        assertSame(record, publisher.publish(record, false));
    }

    @Test
    void copiesAPrivateRecordIntoThePublicContainer() throws IOException {
        when(resolver.multimodalContainer()).thenReturn("static");
        when(resolver.artifactContainer()).thenReturn("artifacts");
        when(resolver.publicArtifactContainer()).thenReturn("public-artifacts");
        var record = file("file-1");
        record.fileName = "clip.mp4";
        record.contentType = "video/mp4";
        record.storagePath = "artifacts/artifacts/file-1.mp4";
        when(storage.headObject("artifacts", "artifacts/file-1.mp4"))
                .thenReturn(new ObjectStorageService.ObjectMetadata(10L, "etag", "video/mp4", "now"));
        downloadWrites("artifacts", "artifacts/file-1.mp4", "same video".getBytes(StandardCharsets.UTF_8));
        when(fileService.fileRecordCollection.find(any(Query.class))).thenReturn(List.of());

        var published = publisher.publish(record, true);

        assertNotSame(record, published);
        assertTrue(published.storagePath.startsWith("public-artifacts/"));
    }

    private void downloadWrites(String container, String blobName, byte[] content) {
        doAnswer(invocation -> {
            Path target = invocation.getArgument(2);
            Files.write(target, content);
            return null;
        }).when(storage).downloadObjectToFile(eq(container), eq(blobName), any(Path.class));
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<FileRecord> fileRecordCollection() {
        return (MongoCollection<FileRecord>) mock(MongoCollection.class);
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
