package ai.core.server.gateway;

import ai.core.server.domain.FileRecord;
import ai.core.server.domain.MediaJob;
import ai.core.server.file.FileService;
import org.jcodec.api.awt.AWTSequenceEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Stephen
 */
class MediaPosterServiceTest {
    @TempDir
    Path tempDir;

    private MediaPosterService service;
    private FileService fileService;
    private MediaJobArtifactStore artifactStore;

    @BeforeEach
    void setUp() {
        service = new MediaPosterService();
        fileService = mock(FileService.class);
        artifactStore = mock(MediaJobArtifactStore.class);
        service.fileService = fileService;
        service.artifactStore = artifactStore;
    }

    @Test
    void extractsAFrameAndStoresAJpegPoster() throws Exception {
        var video = tempDir.resolve("poster-test.mp4");
        encodeVideo(video);
        var record = new FileRecord();
        record.id = "poster-1";
        record.fileName = "media-job-1-poster.jpg";
        record.contentType = "image/jpeg";
        record.size = 100L;
        var uploadedSize = new AtomicLong();
        when(fileService.uploadIfAbsent(any(), any(), any(), any())).thenAnswer(invocation -> {
            Path uploaded = invocation.getArgument(3);
            uploadedSize.set(Files.size(uploaded));
            return record;
        });
        var job = new MediaJob();
        job.id = "job-1";
        job.userId = "user-1";

        var poster = service.generate(job, Files.readAllBytes(video));

        assertSame(record, poster);
        assertTrue(uploadedSize.get() > 0, "the uploaded poster must contain jpeg bytes");
        verify(artifactStore).markPoster(job, record);
    }

    @Test
    void leavesTheArtifactAloneWhenTheVideoCannotBeDecoded() {
        var job = new MediaJob();
        job.id = "job-2";
        job.userId = "user-1";

        var poster = service.generate(job, "not a video".getBytes(StandardCharsets.UTF_8));

        assertNull(poster);
        verify(artifactStore).markPosterAttempted(job);
    }

    private void encodeVideo(Path output) throws Exception {
        var image = new BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.ORANGE);
            graphics.fillRect(8, 8, 24, 18);
        } finally {
            graphics.dispose();
        }
        var encoder = AWTSequenceEncoder.createSequenceEncoder(output.toFile(), 24);
        try {
            for (var i = 0; i < 24; i++) {
                encoder.encodeImage(image);
            }
        } finally {
            encoder.finish();
        }
        assertTrue(Files.size(output) > 0);
    }
}
