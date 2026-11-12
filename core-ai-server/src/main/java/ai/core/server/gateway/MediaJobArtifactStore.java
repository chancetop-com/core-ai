package ai.core.server.gateway;

import ai.core.server.domain.FileRecord;
import ai.core.server.domain.MediaJob;
import ai.core.server.file.FileService;
import core.framework.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static ai.core.server.gateway.GatewaySupport.hasText;

/**
 * Owns the video artifact lifecycle on {@code media_jobs}: claim, store, expire, retry and the queries
 * used by the materialization job. Keeping it apart from {@link MediaJobService} keeps that service
 * focused on job creation, cost and listing.
 *
 * @author Stephen
 */
public class MediaJobArtifactStore {
    private static final int MAX_ARTIFACT_ATTEMPTS = 5;
    private static final int MAX_ARTIFACT_ERROR_LENGTH = 500;

    @Inject
    MediaJobStore mediaJobStore;

    @Inject
    ai.core.server.file.FileRecordStore fileRecordStore;

    @Inject
    FileService fileService;

    /**
     * The video tool saves a downloaded MP4 as {@code video-<gateway handle>.mp4} but has no reference
     * back to the media job. Adopting that record prevents a second download and keeps the video
     * playable after the upstream retention window closes.
     */
    public Optional<FileRecord> findSavedVideo(MediaJob job) {
        if (!hasText(job.userId)) return Optional.empty();
        var names = new ArrayList<String>(2);
        names.add("video-" + GatewayVideoHandle.encode(job.id) + ".mp4");
        names.add("video-" + GatewayVideoHandle.encodeLegacy(job.id) + ".mp4");
        var query = new ai.core.server.file.FileRecordQuery();
        query.userId = job.userId;
        query.fileNames = names;
        query.limit = 1;
        return fileRecordStore.find(query).stream().findFirst();
    }

    public FileRecord storeBytes(MediaJob job, byte[] bytes, String contentType) {
        try {
            var tempFile = Files.createTempFile("media-job-", ".bin");
            Files.write(tempFile, bytes);
            var type = hasText(contentType) ? contentType : "application/octet-stream";
            var record = fileService.uploadIfAbsent(job.userId, "media-" + job.id + FileService.extension(type), type, tempFile);
            markStored(job, record);
            return record;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to cache media job bytes, job=" + job.id, e);
        }
    }

    public boolean claim(String jobId) {
        return mediaJobStore.claimArtifact(jobId);
    }

    public void markStored(MediaJob job, FileRecord record) {
        var now = ZonedDateTime.now();
        job.fileId = record.id;
        job.fileName = record.fileName;
        job.contentType = record.contentType;
        job.artifactState = "stored";
        job.artifactSize = record.size;
        job.artifactStoredAt = now;
        job.updatedAt = now;
        job.artifactError = null;
        job.artifactClaimedAt = null;
        job.artifactNextAttemptAt = null;
        mediaJobStore.replace(job);
    }

    public void markPoster(MediaJob job, FileRecord record) {
        var now = ZonedDateTime.now();
        job.posterFileId = record.id;
        job.posterAttemptedAt = now;
        job.updatedAt = now;
        mediaJobStore.replace(job);
    }

    /**
     * A failed poster attempt is retried by the next day's backfill, not once a minute.
     */
    public void markPosterAttempted(MediaJob job) {
        var now = ZonedDateTime.now();
        job.posterAttemptedAt = now;
        job.updatedAt = now;
        mediaJobStore.replace(job);
    }

    public List<MediaJob> posterCandidates(int limit) {
        return mediaJobStore.posterCandidates(limit);
    }

    public void markUnavailable(MediaJob job, String error) {
        var message = truncate(error);
        job.artifactState = "expired";
        job.artifactError = message;
        job.updatedAt = ZonedDateTime.now();
        job.artifactClaimedAt = null;
        job.artifactNextAttemptAt = null;
        mediaJobStore.replace(job);
    }

    public void markFailed(MediaJob job, String error) {
        var now = ZonedDateTime.now();
        var message = truncate(error);
        var attempts = job.artifactAttempts == null ? 1 : job.artifactAttempts;
        var terminal = attempts >= MAX_ARTIFACT_ATTEMPTS;
        job.artifactState = terminal ? "expired" : "failed";
        job.artifactError = message;
        job.updatedAt = now;
        job.artifactClaimedAt = null;
        job.artifactNextAttemptAt = terminal ? null : now.plus(backoff(attempts));
        mediaJobStore.replace(job);
    }

    public void reset(MediaJob job) {
        job.fileId = null;
        job.artifactState = "pending";
        job.updatedAt = ZonedDateTime.now();
        job.artifactClaimedAt = null;
        mediaJobStore.replace(job);
    }

    public void releaseStaleClaims() {
        mediaJobStore.releaseStaleClaims();
    }

    public List<MediaJob> backfillCandidates(int limit) {
        return mediaJobStore.backfillCandidates(limit);
    }

    public List<MediaJob> staleVideoJobs(int limit) {
        return mediaJobStore.staleVideoJobs(limit);
    }

    private String truncate(String error) {
        if (error == null) return null;
        var value = error.length() > MAX_ARTIFACT_ERROR_LENGTH ? error.substring(0, MAX_ARTIFACT_ERROR_LENGTH) : error;
        return value.replaceAll("data:[^,]+,", "data:...").replaceAll("[A-Za-z0-9+/=]{200,}", "[base64 truncated]");
    }

    private Duration backoff(int attempts) {
        return switch (attempts) {
            case 1 -> Duration.ofMinutes(1);
            case 2 -> Duration.ofMinutes(5);
            case 3 -> Duration.ofMinutes(15);
            case 4 -> Duration.ofHours(1);
            default -> Duration.ofHours(6);
        };
    }
}

