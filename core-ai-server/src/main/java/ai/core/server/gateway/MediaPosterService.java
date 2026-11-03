package ai.core.server.gateway;

import ai.core.server.domain.FileRecord;
import ai.core.server.domain.MediaJob;
import ai.core.server.file.FileService;
import ai.core.server.util.ImageJpegCodec;
import core.framework.inject.Inject;
import org.jcodec.api.FrameGrab;
import org.jcodec.api.JCodecException;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.scale.AWTUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static ai.core.server.gateway.GatewaySupport.hasText;

/**
 * Extracts one video frame and stores it as a small jpeg poster for list tiles and the preview cover.
 * JCodec is pure Java, so the server image does not need an ffmpeg binary; a codec it cannot decode
 * simply leaves the poster empty instead of failing the video archive.
 *
 * @author Stephen
 */
public class MediaPosterService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaPosterService.class);
    private static final double FIRST_FRAME_SECONDS = 1.0;
    private static final int MAX_POSTER_EDGE = 720;
    private static final float JPEG_QUALITY = 0.82f;
    private static final long MAX_POSTER_SOURCE_BYTES = 256L * 1024 * 1024;

    @Inject
    FileService fileService;

    @Inject
    MediaJobArtifactStore artifactStore;

    /**
     * Generates a poster from bytes that are already in memory, so the normal archive path does not
     * download the video a second time.
     */
    public FileRecord generate(MediaJob job, byte[] videoBytes) {
        Path videoFile = null;
        Path posterFile = null;
        try {
            if (videoBytes == null || videoBytes.length == 0) {
                markAttemptedQuietly(job);
                return null;
            }
            videoFile = Files.createTempFile("media-poster-", ".mp4");
            Files.write(videoFile, videoBytes);
            var frame = firstFrame(videoFile);
            if (frame == null) {
                markAttemptedQuietly(job);
                return null;
            }
            var jpeg = ImageJpegCodec.jpeg(frame, MAX_POSTER_EDGE, JPEG_QUALITY);
            if (jpeg == null || jpeg.length == 0) {
                markAttemptedQuietly(job);
                return null;
            }
            posterFile = Files.createTempFile("media-poster-", ".jpg");
            Files.write(posterFile, jpeg);
            var record = fileService.uploadIfAbsent(job.userId, "media-" + job.id + "-poster.jpg", "image/jpeg", posterFile);
            artifactStore.markPoster(job, record);
            return record;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("video poster generation failed, job={}", job.id, e);
            markAttemptedQuietly(job);
            return null;
        } finally {
            deleteQuietly(videoFile);
            deleteQuietly(posterFile);
        }
    }

    /**
     * Backfills posters for videos stored before this service existed; bounded by the caller to keep
     * a background pass predictable.
     */
    public FileRecord generateFromStored(MediaJob job) {
        if (!hasText(job.fileId)) return null;
        try {
            var record = fileService.get(job.fileId);
            if (record.size != null && record.size > MAX_POSTER_SOURCE_BYTES) {
                markAttemptedQuietly(job);
                return null;
            }
            return generate(job, fileService.getBytes(record));
        } catch (RuntimeException e) {
            LOGGER.warn("stored video poster generation failed, job={}", job.id, e);
            markAttemptedQuietly(job);
            return null;
        }
    }

    private void markAttemptedQuietly(MediaJob job) {
        try {
            artifactStore.markPosterAttempted(job);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to record poster attempt, job={}", job.id, e);
        }
    }

    private BufferedImage firstFrame(Path videoFile) {
        for (var seconds : new double[]{FIRST_FRAME_SECONDS, 0.0}) {
            try (var channel = NIOUtils.readableChannel(videoFile.toFile())) {
                var frame = FrameGrab.getFrameFromChannelAtSec(channel, seconds);
                if (frame != null) return AWTUtil.toBufferedImage(frame);
            } catch (IOException | JCodecException | RuntimeException e) {
                LOGGER.debug("video frame not readable at {}s, job file={}", seconds, videoFile, e);
            }
        }
        return null;
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.warn("failed to delete poster temp file, path={}", path, e);
        }
    }
}
