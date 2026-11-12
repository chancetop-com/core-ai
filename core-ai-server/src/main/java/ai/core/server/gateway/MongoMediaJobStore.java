package ai.core.server.gateway;

import ai.core.server.domain.MediaJob;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoMediaJobStore implements MediaJobStore {
    private static final int MAX_ARTIFACT_ATTEMPTS = 5;

    private static Bson attemptsAllowed() {
        return Filters.or(
            Filters.exists("artifact_attempts", false),
            Filters.eq("artifact_attempts", null),
            Filters.lt("artifact_attempts", MAX_ARTIFACT_ATTEMPTS));
    }

    private static Bson nextAttemptAllowed(ZonedDateTime now) {
        return Filters.or(
            Filters.exists("artifact_next_attempt_at", false),
            Filters.eq("artifact_next_attempt_at", null),
            Filters.lte("artifact_next_attempt_at", now));
    }

    public static MongoMediaJobStore of(MongoCollection<MediaJob> collection) {
        var store = new MongoMediaJobStore();
        store.mediaJobCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<MediaJob> mediaJobCollection;

    @Override
    public Optional<MediaJob> get(String id) {
        return mediaJobCollection.get(id);
    }

    @Override
    public void insert(MediaJob job) {
        mediaJobCollection.insert(job);
    }

    @Override
    public void replace(MediaJob job) {
        mediaJobCollection.replace(job);
    }

    @Override
    public Optional<MediaJob> findLatestCompleted(String sessionId, String userId, String mediaType) {
        var filters = new ArrayList<Bson>();
        filters.add(Filters.eq("state", "completed"));
        filters.add(mediaTypeFilter(mediaType));
        if (sessionId == null && userId == null) return Optional.empty();
        if (sessionId != null) filters.add(Filters.eq("session_id", sessionId));
        if (userId != null) filters.add(Filters.eq("user_id", userId));
        var query = new Query();
        query.filter = Filters.and(filters);
        query.sort = Sorts.descending("completed_at");
        query.limit = 1;
        var jobs = mediaJobCollection.find(query);
        return jobs.isEmpty() ? Optional.empty() : Optional.of(jobs.getFirst());
    }

    @Override
    public MediaJobPage list(String mediaType, String costSource, String userId, int offset, int limit) {
        var filters = new ArrayList<Bson>();
        if (mediaType != null && !mediaType.isBlank()) filters.add(mediaTypeFilter(mediaType));
        if (costSource != null && !costSource.isBlank()) filters.add(Filters.eq("cost_source", costSource));
        if (userId != null && !userId.isBlank()) filters.add(Filters.eq("user_id", userId));
        var filter = filters.isEmpty() ? Filters.empty()
            : filters.size() == 1 ? filters.getFirst() : Filters.and(filters);
        var query = new Query();
        query.filter = filter;
        query.sort = Sorts.descending("created_at");
        query.skip = offset;
        query.limit = limit;
        return new MediaJobPage(mediaJobCollection.count(filter), mediaJobCollection.find(query));
    }

    @Override
    public boolean claimArtifact(String jobId) {
        var now = ZonedDateTime.now();
        mediaJobCollection.update(
            Filters.and(Filters.eq("_id", jobId), Filters.eq("artifact_attempts", null)),
            Updates.set("artifact_attempts", 0));
        return mediaJobCollection.update(
            Filters.and(
                Filters.eq("_id", jobId),
                Filters.eq("state", "completed"),
                Filters.eq("file_id", null),
                Filters.in("media_type", Arrays.asList("video", null)),
                Filters.or(
                    Filters.exists("artifact_state", false),
                    Filters.eq("artifact_state", null),
                    Filters.in("artifact_state", List.of("pending", "failed"))),
                attemptsAllowed(),
                nextAttemptAllowed(now)),
            Updates.combine(
                Updates.set("artifact_state", "storing"),
                Updates.set("artifact_claimed_at", now),
                Updates.set("updated_at", now),
                Updates.inc("artifact_attempts", 1))) > 0;
    }

    @Override
    public void releaseStaleClaims() {
        mediaJobCollection.update(
            Filters.and(
                Filters.eq("artifact_state", "storing"),
                Filters.lt("artifact_claimed_at", ZonedDateTime.now().minusMinutes(10))),
            Updates.combine(
                Updates.set("artifact_state", "pending"),
                Updates.unset("artifact_claimed_at")));
    }

    @Override
    public List<MediaJob> posterCandidates(int limit) {
        var query = new Query();
        query.filter = Filters.and(
            Filters.in("media_type", Arrays.asList("video", null)),
            Filters.eq("state", "completed"),
            Filters.ne("file_id", null),
            Filters.eq("poster_file_id", null),
            Filters.or(
                Filters.exists("poster_attempted_at", false),
                Filters.eq("poster_attempted_at", null),
                Filters.lt("poster_attempted_at", ZonedDateTime.now().minusDays(1))));
        query.sort = Sorts.ascending("artifact_stored_at");
        query.limit = limit;
        return mediaJobCollection.find(query);
    }

    @Override
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
            attemptsAllowed(),
            nextAttemptAllowed(ZonedDateTime.now()));
        query.sort = Sorts.ascending("completed_at");
        query.limit = limit;
        return mediaJobCollection.find(query);
    }

    @Override
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

    private Bson mediaTypeFilter(String mediaType) {
        return "video".equals(mediaType)
            ? Filters.in("media_type", Arrays.asList("video", null))
            : Filters.eq("media_type", "image");
    }
}
