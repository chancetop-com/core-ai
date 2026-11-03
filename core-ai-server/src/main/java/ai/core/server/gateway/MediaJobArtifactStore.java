package ai.core.server.gateway;

import ai.core.server.domain.FileRecord;
import ai.core.server.domain.MediaJob;
import ai.core.server.file.FileService;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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
    MongoCollection<MediaJob> mediaJobCollection;

    @Inject
    MongoCollection<FileRecord> fileRecordCollection;

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
        var query = new Query();
        query.filter = Filters.and(
                Filters.eq("user_id", job.userId),
                Filters.in("file_name", names));
        query.sort = Sorts.descending("created_at");
        query.limit = 1;
        return fileRecordCollection.find(query).stream().findFirst();
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
        var now = ZonedDateTime.now();
        var updated = mediaJobCollection.update(
                Filters.and(
                        Filters.eq("_id", jobId),
                        Filters.eq("state", "completed"),
                        Filters.eq("file_id", null),
                        Filters.in("media_type", Arrays.asList("video", null)),
                        Filters.or(
                                Filters.exists("artifact_state", false),
                                Filters.eq("artifact_state", null),
                                Filters.in("artifact_state", List.of("pending", "failed"))),
                        Filters.or(
                                Filters.exists("artifact_attempts", false),
                                Filters.lt("artifact_attempts", MAX_ARTIFACT_ATTEMPTS)),
                        Filters.or(
                                Filters.exists("artifact_next_attempt_at", false),
                                Filters.lte("artifact_next_attempt_at", now))),
                Updates.combine(
                        Updates.set("artifact_state", "storing"),
                        Updates.set("artifact_claimed_at", now),
                        Updates.set("updated_at", now),
                        Updates.inc("artifact_attempts", 1)));
        return updated > 0;
    }

    public void markStored(MediaJob job, FileRecord record) {
        var now = ZonedDateTime.now();
        mediaJobCollection.update(Filters.eq("_id", job.id), Updates.combine(
                Updates.set("file_id", record.id),
                Updates.set("file_name", record.fileName),
                Updates.set("content_type", record.contentType),
                Updates.set("artifact_state", "stored"),
                Updates.set("artifact_size", record.size),
                Updates.set("artifact_stored_at", now),
                Updates.set("updated_at", now),
                Updates.unset("artifact_error"),
                Updates.unset("artifact_claimed_at"),
                Updates.unset("artifact_next_attempt_at")));
        job.fileId = record.id;
        job.fileName = record.fileName;
        job.contentType = record.contentType;
        job.artifactState = "stored";
        job.artifactSize = record.size;
        job.artifactStoredAt = now;
        job.artifactError = null;
        job.artifactClaimedAt = null;
        job.artifactNextAttemptAt = null;
    }

    public void markPoster(MediaJob job, FileRecord record) {
        var now = ZonedDateTime.now();
        mediaJobCollection.update(Filters.eq("_id", job.id), Updates.combine(
                Updates.set("poster_file_id", record.id),
                Updates.set("poster_attempted_at", now),
                Updates.set("updated_at", now)));
        job.posterFileId = record.id;
        job.posterAttemptedAt = now;
    }

    /**
     * A failed poster attempt is retried by the next day's backfill, not once a minute.
     */
    public void markPosterAttempted(MediaJob job) {
        var now = ZonedDateTime.now();
        mediaJobCollection.update(Filters.eq("_id", job.id), Updates.combine(
                Updates.set("poster_attempted_at", now),
                Updates.set("updated_at", now)));
        job.posterAttemptedAt = now;
    }

    public List<MediaJob> posterCandidates(int limit) {
        var query = new Query();
        query.filter = Filters.and(
                Filters.in("media_type", Arrays.asList("video", null)),
                Filters.eq("state", "completed"),
                Filters.ne("file_id", null),
                Filters.eq("poster_file_id", null),
                Filters.or(
                        Filters.exists("poster_attempted_at", false),
                        Filters.lt("poster_attempted_at", ZonedDateTime.now().minusDays(1))));
        query.sort = Sorts.ascending("artifact_stored_at");
        query.limit = limit;
        return mediaJobCollection.find(query);
    }

    public void markUnavailable(MediaJob job, String error) {
        var message = truncate(error);
        mediaJobCollection.update(Filters.eq("_id", job.id), Updates.combine(
                Updates.set("artifact_state", "expired"),
                Updates.set("artifact_error", message),
                Updates.set("updated_at", ZonedDateTime.now()),
                Updates.unset("artifact_claimed_at"),
                Updates.unset("artifact_next_attempt_at")));
        job.artifactState = "expired";
        job.artifactError = message;
        job.artifactClaimedAt = null;
        job.artifactNextAttemptAt = null;
    }

    public void markFailed(MediaJob job, String error) {
        var now = ZonedDateTime.now();
        var message = truncate(error);
        var attempts = job.artifactAttempts == null ? 1 : job.artifactAttempts;
        var terminal = attempts >= MAX_ARTIFACT_ATTEMPTS;
        var nextAttempt = terminal ? null : now.plus(backoff(attempts));
        var updates = Updates.combine(
                Updates.set("artifact_state", terminal ? "expired" : "failed"),
                Updates.set("artifact_error", message),
                Updates.set("updated_at", now),
                Updates.unset("artifact_claimed_at"));
        updates = nextAttempt == null
                ? Updates.combine(updates, Updates.unset("artifact_next_attempt_at"))
                : Updates.combine(updates, Updates.set("artifact_next_attempt_at", nextAttempt));
        mediaJobCollection.update(Filters.eq("_id", job.id), updates);
        job.artifactState = terminal ? "expired" : "failed";
        job.artifactError = message;
        job.artifactClaimedAt = null;
        job.artifactNextAttemptAt = nextAttempt;
    }

    public void reset(MediaJob job) {
        mediaJobCollection.update(Filters.eq("_id", job.id), Updates.combine(
                Updates.set("file_id", null),
                Updates.set("artifact_state", "pending"),
                Updates.set("updated_at", ZonedDateTime.now()),
                Updates.unset("artifact_claimed_at")));
        job.fileId = null;
        job.artifactState = "pending";
    }

    public void releaseStaleClaims() {
        mediaJobCollection.update(
                Filters.and(
                        Filters.eq("artifact_state", "storing"),
                        Filters.lt("artifact_claimed_at", ZonedDateTime.now().minusMinutes(10))),
                Updates.combine(
                        Updates.set("artifact_state", "pending"),
                        Updates.unset("artifact_claimed_at")));
    }

    public List<MediaJob> backfillCandidates(int limit) {
        var query = new Query();
        query.filter = Filters.and(
                Filters.in("media_type", Arrays.asList("video", null)),
                Filters.eq("state", "completed"),
                Filters.eq("file_id", null),
                Filters.or(
                        Filters.exists("artifact_state", false),
                        Filters.eq("artifact_state", null),
                        Filters.in("artifact_state", List.of("pending", "failed"))),
                Filters.or(
                        Filters.exists("artifact_attempts", false),
                        Filters.lt("artifact_attempts", MAX_ARTIFACT_ATTEMPTS)),
                Filters.or(
                        Filters.exists("artifact_next_attempt_at", false),
                        Filters.lte("artifact_next_attempt_at", ZonedDateTime.now())));
        query.sort = Sorts.ascending("completed_at");
        query.limit = limit;
        return mediaJobCollection.find(query);
    }

    public List<MediaJob> staleVideoJobs(int limit) {
        var now = ZonedDateTime.now();
        var query = new Query();
        query.filter = Filters.and(
                Filters.in("media_type", Arrays.asList("video", null)),
                Filters.in("state", List.of("submitted", "queued", "processing")),
                Filters.lt("updated_at", now.minusMinutes(2)),
                Filters.gte("created_at", now.minusDays(7)));
        query.sort = Sorts.ascending("updated_at");
        query.limit = limit;
        return mediaJobCollection.find(query);
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
