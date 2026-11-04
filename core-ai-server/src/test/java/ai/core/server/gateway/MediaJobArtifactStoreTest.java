package ai.core.server.gateway;

import ai.core.server.domain.FileRecord;
import ai.core.server.domain.MediaJob;
import ai.core.server.file.FileService;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Stephen
 */
class MediaJobArtifactStoreTest {
    private MediaJobArtifactStore store;
    private MongoCollection<MediaJob> collection;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = new MediaJobArtifactStore();
        collection = (MongoCollection<MediaJob>) mock(MongoCollection.class);
        store.mediaJobCollection = collection;
        store.fileRecordCollection = (MongoCollection<FileRecord>) mock(MongoCollection.class);
        store.fileService = mock(FileService.class);
    }

    @Test
    void findsTheMp4SavedByTheVideoTool() {
        var job = new MediaJob();
        job.id = "job-1";
        job.userId = "user-1";
        var record = new FileRecord();
        record.id = "file-1";
        when(store.fileRecordCollection.find(any(Query.class))).thenReturn(List.of(record));

        assertSame(record, store.findSavedVideo(job).orElseThrow());

        var captor = ArgumentCaptor.forClass(Query.class);
        verify(store.fileRecordCollection).find(captor.capture());
        var filter = captor.getValue().filter.toString();
        assertTrue(filter.contains("video-" + GatewayVideoHandle.encode("job-1") + ".mp4"));
        assertTrue(filter.contains("video-" + GatewayVideoHandle.encodeLegacy("job-1") + ".mp4"));
        assertTrue(filter.contains("user-1"));
    }

    @Test
    void storeBytesMarksArtifactStored() {
        var job = new MediaJob();
        job.id = "job-1";
        job.userId = "user-1";
        var record = new FileRecord();
        record.id = "file-1";
        record.fileName = "media-job-1.mp4";
        record.contentType = "video/mp4";
        record.size = 11L;
        when(store.fileService.uploadIfAbsent(any(), any(), any(), any())).thenReturn(record);

        var stored = store.storeBytes(job, "video-bytes".getBytes(StandardCharsets.UTF_8), "video/mp4");

        assertSame(record, stored);
        assertEquals("file-1", job.fileId);
        assertEquals("stored", job.artifactState);
        assertEquals(11L, job.artifactSize);
        var update = capturedUpdateText();
        assertTrue(update.contains("fieldName='file_id', operator='$set', value=file-1"));
        assertTrue(update.contains("fieldName='artifact_state', operator='$set', value=stored"));
        assertTrue(update.contains("fieldName='artifact_size', operator='$set', value=11"));
    }

    @Test
    void claimReturnsWhetherTheConditionalUpdateMatched() {
        when(collection.update(any(Bson.class), any(Bson.class))).thenReturn(0L);
        assertFalse(store.claim("job-1"));

        when(collection.update(any(Bson.class), any(Bson.class))).thenReturn(1L);
        assertTrue(store.claim("job-1"));
    }

    @Test
    void claimMatchesJobsWithExplicitNullRetryFields() {
        when(collection.update(any(Bson.class), any(Bson.class))).thenReturn(0L);

        store.claim("job-1");

        var captor = ArgumentCaptor.forClass(Bson.class);
        verify(collection).update(captor.capture(), any(Bson.class));
        var filter = captor.getValue().toString();
        assertTrue(filter.contains("Filter{fieldName='artifact_attempts', value=null}"));
        assertTrue(filter.contains("Filter{fieldName='artifact_next_attempt_at', value=null}"));
    }

    @Test
    void markUnavailableWritesExpiredState() {
        var job = new MediaJob();
        job.id = "job-1";

        store.markUnavailable(job, "HTTP 404: gone");

        var update = capturedUpdateText();
        assertTrue(update.contains("fieldName='artifact_state', operator='$set', value=expired"));
        assertTrue(update.contains("fieldName='artifact_error', operator='$set', value=HTTP 404: gone"));
        assertEquals("expired", job.artifactState);
    }

    private String capturedUpdateText() {
        ArgumentCaptor<Bson> captor = ArgumentCaptor.forClass(Bson.class);
        verify(collection).update(any(Bson.class), captor.capture());
        return captor.getValue().toString();
    }
}
