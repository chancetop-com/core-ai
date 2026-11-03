package ai.core.server.gateway;

import ai.core.server.domain.FileRecord;
import ai.core.server.domain.MediaJob;
import ai.core.server.file.FileService;
import core.framework.inject.Inject;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

import static ai.core.server.gateway.GatewaySupport.hasText;

/**
 * Downloads completed video artifacts once and keeps them in the platform file store, so the generations
 * page never depends on an upstream interaction or signed URL still being alive.
 *
 * @author Stephen
 */
public class MediaArtifactService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaArtifactService.class);
    private static final long MAX_VIDEO_BYTES = 512L * 1024 * 1024;

    static boolean isUnavailableError(Throwable error) {
        for (var current = error; current != null; current = current.getCause()) {
            var value = current.getMessage();
            if (value == null) continue;
            var lower = value.toLowerCase(Locale.ROOT);
            if (lower.contains("http 404")
                    || lower.contains("http 410")
                    || lower.contains("not found")
                    || lower.contains("not_found")
                    || lower.contains("requested entity was not found")) {
                return true;
            }
        }
        return false;
    }

    @Inject
    MediaJobService mediaJobService;

    @Inject
    MediaJobArtifactStore artifactStore;

    @Inject
    GatewayMediaProvider mediaProvider;

    @Inject
    FileService fileService;

    @Inject
    MediaPosterService posterService;

    public StoredArtifact ensureStored(MediaJob job) {
        var stored = existing(job);
        if (stored != null) return stored;
        ensureCompleted(job);
        var saved = artifactStore.findSavedVideo(job);
        if (saved.isPresent()) {
            artifactStore.markStored(job, saved.get());
            return new StoredArtifact(saved.get(), null);
        }
        ensureNotExpired(job);
        if (!artifactStore.claim(job.id)) {
            var current = mediaJobService.get(job.id);
            var currentStored = existing(current);
            if (currentStored != null) return currentStored;
            ensureNotExpired(current);
            throw new MediaArtifactNotReadyException("video artifact is being archived");
        }
        job.artifactAttempts = job.artifactAttempts == null ? 1 : job.artifactAttempts + 1;
        return downloadAndStore(job);
    }

    public byte[] bytes(FileRecord record) {
        return fileService.getBytes(record);
    }

    private StoredArtifact existing(MediaJob job) {
        if (!hasText(job.fileId)) return null;
        try {
            return new StoredArtifact(fileService.get(job.fileId), null);
        } catch (NotFoundException e) {
            LOGGER.warn("media artifact file record missing, job={}, fileId={}", job.id, job.fileId);
            artifactStore.reset(job);
            return null;
        }
    }

    private void ensureCompleted(MediaJob job) {
        if (!"completed".equals(job.state)) {
            throw new MediaArtifactNotReadyException("video is not completed, state=" + job.state);
        }
    }

    private void ensureNotExpired(MediaJob job) {
        if ("expired".equals(job.artifactState)) {
            throw new MediaArtifactUnavailableException(job.artifactError == null
                    ? "video artifact expired upstream" : job.artifactError);
        }
    }

    private StoredArtifact downloadAndStore(MediaJob job) {
        byte[] bytes;
        try {
            bytes = mediaProvider.downloadVideoBytes(job);
        } catch (MediaArtifactUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            fail(job, e);
            throw e;
        }
        if (bytes == null || bytes.length == 0) {
            var error = new MediaArtifactUnavailableException("upstream video is empty");
            artifactStore.markUnavailable(job, error.getMessage());
            throw error;
        }
        if (bytes.length > MAX_VIDEO_BYTES) {
            var error = new IllegalStateException("video is too large: " + bytes.length + " bytes");
            artifactStore.markFailed(job, error.getMessage());
            throw error;
        }
        var record = storeVideo(job, bytes);
        try {
            posterService.generate(job, bytes);
        } catch (RuntimeException e) {
            LOGGER.warn("poster generation failed, job={}", job.id, e);
        }
        LOGGER.info("media artifact stored, job={}, bytes={}", job.id, bytes.length);
        return new StoredArtifact(record, bytes);
    }

    private FileRecord storeVideo(MediaJob job, byte[] bytes) {
        try {
            return artifactStore.storeBytes(job, bytes, "video/mp4");
        } catch (RuntimeException e) {
            artifactStore.markFailed(job, message(e));
            throw e;
        }
    }

    private void fail(MediaJob job, RuntimeException e) {
        if (isUnavailableError(e)) {
            artifactStore.markUnavailable(job, message(e));
            throw new MediaArtifactUnavailableException("upstream video is no longer available: " + message(e), e);
        }
        artifactStore.markFailed(job, message(e));
    }

    private String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    public record StoredArtifact(FileRecord record, byte[] freshBytes) {
    }
}
