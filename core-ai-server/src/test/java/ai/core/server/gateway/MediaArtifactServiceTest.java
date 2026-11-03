package ai.core.server.gateway;

import ai.core.server.domain.FileRecord;
import ai.core.server.domain.MediaJob;
import ai.core.server.file.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Stephen
 */
class MediaArtifactServiceTest {
    private MediaArtifactService service;
    private MediaJobService mediaJobService;
    private MediaJobArtifactStore artifactStore;
    private GatewayMediaProvider mediaProvider;
    private FileService fileService;
    private MediaPosterService posterService;

    @BeforeEach
    void setUp() {
        service = new MediaArtifactService();
        mediaJobService = mock(MediaJobService.class);
        artifactStore = mock(MediaJobArtifactStore.class);
        mediaProvider = mock(GatewayMediaProvider.class);
        fileService = mock(FileService.class);
        posterService = mock(MediaPosterService.class);
        service.mediaJobService = mediaJobService;
        service.artifactStore = artifactStore;
        service.mediaProvider = mediaProvider;
        service.fileService = fileService;
        service.posterService = posterService;
        when(artifactStore.findSavedVideo(any())).thenReturn(Optional.empty());
    }

    private MediaJob completedVideo() {
        var job = new MediaJob();
        job.id = "job-1";
        job.state = "completed";
        job.mediaType = "video";
        return job;
    }

    private FileRecord record() {
        var record = new FileRecord();
        record.id = "file-1";
        record.fileName = "media-job-1.mp4";
        record.contentType = "video/mp4";
        record.size = 5L;
        return record;
    }

    @Test
    void returnsStoredRecordWithoutTouchingTheProvider() {
        var job = completedVideo();
        job.fileId = "file-1";
        var record = record();
        when(fileService.get("file-1")).thenReturn(record);

        var artifact = service.ensureStored(job);

        assertSame(record, artifact.record());
        assertNull(artifact.freshBytes());
        verify(mediaProvider, never()).downloadVideoBytes(any());
    }

    @Test
    void adoptsTheVideoAlreadySavedByTheTool() {
        var job = completedVideo();
        job.userId = "user-1";
        var record = record();
        when(artifactStore.findSavedVideo(job)).thenReturn(Optional.of(record));

        var artifact = service.ensureStored(job);

        assertSame(record, artifact.record());
        assertNull(artifact.freshBytes());
        verify(artifactStore).markStored(job, record);
        verify(artifactStore, never()).claim(any());
        verify(mediaProvider, never()).downloadVideoBytes(any());
    }

    @Test
    void downloadsStoresAndReturnsFreshBytes() {
        var job = completedVideo();
        var bytes = "video-bytes".getBytes(StandardCharsets.UTF_8);
        var record = record();
        when(artifactStore.claim("job-1")).thenReturn(Boolean.TRUE);
        when(mediaProvider.downloadVideoBytes(job)).thenReturn(bytes);
        when(artifactStore.storeBytes(job, bytes, "video/mp4")).thenReturn(record);

        var artifact = service.ensureStored(job);

        assertSame(record, artifact.record());
        assertArrayEquals(bytes, artifact.freshBytes());
        verify(artifactStore).storeBytes(job, bytes, "video/mp4");
        verify(posterService).generate(job, bytes);
    }

    @Test
    void marksArtifactExpiredWhenUpstreamReturns404() {
        var job = completedVideo();
        when(artifactStore.claim("job-1")).thenReturn(Boolean.TRUE);
        when(mediaProvider.downloadVideoBytes(job))
                .thenThrow(new RuntimeException("video download failed: HTTP 404: Requested entity was not found"));

        assertThrows(MediaArtifactUnavailableException.class, () -> service.ensureStored(job));

        verify(artifactStore).markUnavailable(eq(job), contains("HTTP 404"));
    }

    @Test
    void marksArtifactFailedWhenUpstreamFailsTransiently() {
        var job = completedVideo();
        when(artifactStore.claim("job-1")).thenReturn(Boolean.TRUE);
        when(mediaProvider.downloadVideoBytes(job)).thenThrow(new RuntimeException("video download failed: HTTP 500"));

        assertThrows(RuntimeException.class, () -> service.ensureStored(job));

        verify(artifactStore).markFailed(eq(job), contains("HTTP 500"));
        verify(artifactStore, never()).markUnavailable(any(), any());
    }

    @Test
    void reportsNotReadyWhenAnotherWorkerOwnsTheClaim() {
        var job = completedVideo();
        when(artifactStore.claim("job-1")).thenReturn(Boolean.FALSE);
        when(mediaJobService.get("job-1")).thenReturn(job);

        assertThrows(MediaArtifactNotReadyException.class, () -> service.ensureStored(job));
        verify(mediaProvider, never()).downloadVideoBytes(any());
    }

    @Test
    void reportsExpiredWithoutAttemptingADownload() {
        var job = completedVideo();
        job.artifactState = "expired";
        job.artifactError = "upstream gone";

        assertThrows(MediaArtifactUnavailableException.class, () -> service.ensureStored(job));
        verify(mediaProvider, never()).downloadVideoBytes(any());
    }
}
