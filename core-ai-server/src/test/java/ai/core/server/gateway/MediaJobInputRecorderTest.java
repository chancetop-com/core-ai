package ai.core.server.gateway;

import ai.core.media.domain.MediaReference;
import ai.core.media.reference.MediaReferenceRole;
import ai.core.media.reference.RemoteMediaLoader;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.MediaJob;
import ai.core.server.domain.MediaJobInput;
import ai.core.server.file.FileService;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class MediaJobInputRecorderTest {
    private MongoCollection<MediaJob> mediaJobs;
    private FileService fileService;
    private RemoteMediaLoader remoteMediaLoader;
    private MediaJobInputRecorder recorder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        var mediaJobService = new MediaJobService();
        mediaJobs = (MongoCollection<MediaJob>) mock(MongoCollection.class);
        fileService = mock(FileService.class);
        mediaJobService.mediaJobCollection = mediaJobs;
        mediaJobService.fileService = fileService;
        remoteMediaLoader = mock(RemoteMediaLoader.class);
        recorder = new MediaJobInputRecorder(mediaJobService, remoteMediaLoader);
    }

    @Test
    void symbolicReferenceIsAnchoredToTheProducingJobWithoutMovingBytes() {
        when(mediaJobs.get("job-1")).thenReturn(Optional.of(completedImage("job-1", "file-9")));

        var inputs = recorder.capture(owner(), List.of(MediaReference.ofMediaId(GatewayMediaHandle.encodeImage("job-1"),
                "char_lin", MediaReferenceRole.SUBJECT)), null, null);

        var input = only(inputs);
        assertEquals(MediaJobInput.KIND_MEDIA, input.kind);
        assertEquals("job-1", input.jobId);
        assertEquals("file-9", input.fileId);
        assertEquals("image/png", input.contentType);
        assertEquals("image", input.modality);
        assertEquals("char_lin", input.name);
        assertEquals("subject", input.role);
        verify(fileService, never()).uploadIfAbsent(any(), any(), any(), any());
    }

    @Test
    void lastResolvesToTheSessionsNewestCompletedGeneration() {
        when(mediaJobs.find(any(Query.class))).thenReturn(List.of(completedImage("job-2", "file-2")));

        var inputs = recorder.capture(owner(), List.of(new MediaReference(null, null, "last", null, null, null)), null, null);

        var input = only(inputs);
        assertEquals(MediaJobInput.KIND_MEDIA, input.kind);
        assertEquals("job-2", input.jobId);
        assertEquals("file-2", input.fileId);
    }

    @Test
    void inlineReferenceIsPersistedSoItOutlivesTheRequest() {
        when(fileService.uploadIfAbsent(any(), any(), any(), any())).thenReturn(file("file-3"));

        var inputs = recorder.capture(owner(), List.of(new MediaReference(null, dataUrl("image/jpeg", "jpeg-bytes"))), null, null);

        var input = only(inputs);
        assertEquals(MediaJobInput.KIND_INLINE, input.kind);
        assertEquals("file-3", input.fileId);
        assertEquals("image/jpeg", input.contentType);
        assertEquals("image", input.modality);
        verify(fileService).uploadIfAbsent(eq("user-1"), eq("reference.jpg"), eq("image/jpeg"), any());
    }

    @Test
    void externalReferenceIsDownloadedAndItsUrlKept() {
        var loaded = new RemoteMediaLoader.Loaded("png-bytes".getBytes(StandardCharsets.UTF_8), "image/png");
        when(remoteMediaLoader.load("https://example.com/ref.png")).thenReturn(loaded);
        when(fileService.uploadIfAbsent(any(), any(), any(), any())).thenReturn(file("file-5"));

        var inputs = recorder.capture(owner(), List.of(new MediaReference("https://example.com/ref.png", null)), null, null);

        var input = only(inputs);
        assertEquals(MediaJobInput.KIND_URL, input.kind);
        assertEquals("https://example.com/ref.png", input.url);
        assertEquals("file-5", input.fileId);
        assertEquals("image/png", input.contentType);
    }

    @Test
    void maskAndEditedFromVideoAreRecordedWithTheirOwnRoles() {
        when(fileService.uploadIfAbsent(any(), any(), any(), any())).thenReturn(file("file-4"));
        var previous = new MediaJob();
        previous.id = "job-v";
        previous.fileId = "file-v";

        var inputs = recorder.capture(owner(), null, new MediaReference(null, dataUrl("image/png", "mask-bytes")), previous);

        assertEquals(2, inputs.size());
        var editedFrom = inputs.getFirst();
        assertEquals(MediaJobInput.ROLE_PREVIOUS_VIDEO, editedFrom.role);
        assertEquals("job-v", editedFrom.jobId);
        assertEquals("video", editedFrom.modality, "a video job that predates media_type is still a video");
        assertEquals(MediaJobInput.ROLE_MASK, inputs.get(1).role);
        assertEquals("file-4", inputs.get(1).fileId);
    }

    @Test
    void unresolvableReferenceIsDroppedInsteadOfFailingTheGeneration() {
        when(mediaJobs.get("job-gone")).thenReturn(Optional.empty());

        var inputs = recorder.capture(owner(), List.of(
                MediaReference.ofMediaId(GatewayMediaHandle.encodeImage("job-gone"), null, null),
                MediaReference.ofMediaId("not-a-gateway-handle", null, null)), null, null);

        assertTrue(inputs.isEmpty());
    }

    private MediaJobInput only(List<MediaJobInput> inputs) {
        assertEquals(1, inputs.size());
        return inputs.getFirst();
    }

    private MediaJobOwner owner() {
        return new MediaJobOwner("user-1", "session-1", null);
    }

    private MediaJob completedImage(String id, String fileId) {
        var job = new MediaJob();
        job.id = id;
        job.mediaType = "image";
        job.fileId = fileId;
        job.contentType = "image/png";
        return job;
    }

    private FileRecord file(String id) {
        var record = new FileRecord();
        record.id = id;
        return record;
    }

    private String dataUrl(String contentType, String value) {
        return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
