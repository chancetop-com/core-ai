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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Stephen
 */
class MediaJobArtifactStoreTest {
    private MediaJobArtifactStore store;
    private MongoCollection<MediaJob> collection;
    private MongoCollection<FileRecord> fileRecordCollection;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = new MediaJobArtifactStore();
        collection = (MongoCollection<MediaJob>) mock(MongoCollection.class);
        store.mediaJobStore = MongoMediaJobStore.of(collection);
        fileRecordCollection = (MongoCollection<FileRecord>) mock(MongoCollection.class);
        store.fileRecordStore = ai.core.server.file.MongoFileRecordStore.of(fileRecordCollection);
        store.fileService = mock(FileService.class);
    }

    @Test
    void findsTheMp4SavedByTheVideoTool() {
        var job = new MediaJob();
        job.id = "job-1";
        job.userId = "user-1";
        var record = new FileRecord();
        record.id = "file-1";
        when(fileRecordCollection.find(any(Query.class))).thenReturn(List.of(record));

        assertSame(record, store.findSavedVideo(job).orElseThrow());

        var captor = ArgumentCaptor.forClass(Query.class);
        verify(fileRecordCollection).find(captor.capture());
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
        assertEquals("file-1", capturedReplace().fileId);
        assertEquals("stored", capturedReplace().artifactState);
        assertEquals(11L, capturedReplace().artifactSize);
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
        verify(collection, times(2)).update(captor.capture(), any(Bson.class));
        var filters = captor.getAllValues().stream().map(Object::toString).toList();
        assertTrue(filters.stream().anyMatch(filter -> filter.contains("Filter{fieldName='artifact_attempts', value=null}")
                && !filter.contains("value=completed")));
        assertTrue(filters.stream().anyMatch(filter -> filter.contains("Filter{fieldName='artifact_attempts', value=null}")
                && filter.contains("value=completed")
                && filter.contains("Filter{fieldName='artifact_next_attempt_at', value=null}")));
    }

    @Test
    void markUnavailableWritesExpiredState() {
        var job = new MediaJob();
        job.id = "job-1";

        store.markUnavailable(job, "HTTP 404: gone");

        assertEquals("expired", capturedReplace().artifactState);
        assertEquals("HTTP 404: gone", capturedReplace().artifactError);
        assertEquals("expired", job.artifactState);
    }

    private MediaJob capturedReplace() {
        var captor = ArgumentCaptor.forClass(MediaJob.class);
        verify(collection, atLeastOnce()).replace(captor.capture());
        return captor.getValue();
    }
}


