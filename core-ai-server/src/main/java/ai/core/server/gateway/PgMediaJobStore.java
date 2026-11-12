package ai.core.server.gateway;

import ai.core.server.domain.MediaJob;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgMediaJobStore implements MediaJobStore {
    private static final String TABLE = "app.media_jobs";
    private static final String SELECT = "SELECT id, user_id, session_id, agent_run_id, state, media_type, cost_source, "
        + "file_id, artifact_state, artifact_attempts, artifact_claimed_at, artifact_next_attempt_at, poster_file_id, "
        + "poster_attempted_at, artifact_stored_at, updated_at, created_at, completed_at, data::text AS data FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, user_id, session_id, agent_run_id, state, "
        + "media_type, cost_source, file_id, artifact_state, artifact_attempts, artifact_claimed_at, "
        + "artifact_next_attempt_at, poster_file_id, poster_attempted_at, artifact_stored_at, updated_at, created_at, "
        + "completed_at, data) ";

    private static MediaJob toJob(PgMediaJobRow row) {
        var job = JsonUtil.fromJson(MediaJob.class, row.data);
        job.id = row.id;
        job.userId = row.userId;
        job.sessionId = row.sessionId;
        job.agentRunId = row.agentRunId;
        job.state = row.state;
        job.mediaType = row.mediaType;
        job.costSource = row.costSource;
        job.fileId = row.fileId;
        job.artifactState = row.artifactState;
        job.artifactAttempts = row.artifactAttempts;
        job.artifactClaimedAt = row.artifactClaimedAt;
        job.artifactNextAttemptAt = row.artifactNextAttemptAt;
        job.posterFileId = row.posterFileId;
        job.posterAttemptedAt = row.posterAttemptedAt;
        job.artifactStoredAt = row.artifactStoredAt;
        job.updatedAt = row.updatedAt;
        job.createdAt = row.createdAt;
        job.completedAt = row.completedAt;
        return job;
    }

    @Inject
    public Database database;

    @Override
    public Optional<MediaJob> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgMediaJobRow.class, id)
            .map(PgMediaJobStore::toJob);
    }

    @Override
    public void insert(MediaJob job) {
        database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)", params(job));
    }

    @Override
    public void replace(MediaJob job) {
        database.execute("UPDATE " + TABLE + " SET user_id = ?, session_id = ?, agent_run_id = ?, state = ?, "
                + "media_type = ?, cost_source = ?, file_id = ?, artifact_state = ?, artifact_attempts = ?, "
                + "artifact_claimed_at = ?, artifact_next_attempt_at = ?, poster_file_id = ?, poster_attempted_at = ?, "
                + "artifact_stored_at = ?, updated_at = ?, created_at = ?, completed_at = ?, data = ?::jsonb WHERE id = ?",
            job.userId, job.sessionId, job.agentRunId, job.state, job.mediaType, job.costSource, job.fileId,
            job.artifactState, job.artifactAttempts, job.artifactClaimedAt, job.artifactNextAttemptAt,
            job.posterFileId, job.posterAttemptedAt, job.artifactStoredAt, job.updatedAt, job.createdAt,
            job.completedAt, JsonUtil.toJson(job), job.id);
    }

    @Override
    public Optional<MediaJob> findLatestCompleted(String sessionId, String userId, String mediaType) {
        if (sessionId == null && userId == null) return Optional.empty();
        var where = new ArrayList<String>();
        var params = new ArrayList<>();
        where.add("state = ?");
        params.add("completed");
        addMediaCondition(where, params, mediaType);
        if (sessionId != null) {
            where.add("session_id = ?");
            params.add(sessionId);
        }
        if (userId != null) {
            where.add("user_id = ?");
            params.add(userId);
        }
        var sql = SELECT + " WHERE " + String.join(" AND ", where)
            + " ORDER BY completed_at DESC NULLS LAST, id DESC LIMIT 1";
        return database.select(sql, PgMediaJobRow.class, params.toArray())
            .stream().map(PgMediaJobStore::toJob).findFirst();
    }

    @Override
    public MediaJobPage list(String mediaType, String costSource, String userId, int offset, int limit) {
        var where = new ArrayList<String>();
        var params = new ArrayList<>();
        if (mediaType != null && !mediaType.isBlank()) addMediaCondition(where, params, mediaType);
        if (costSource != null && !costSource.isBlank()) {
            where.add("cost_source = ?");
            params.add(costSource);
        }
        if (userId != null && !userId.isBlank()) {
            where.add("user_id = ?");
            params.add(userId);
        }
        var whereClause = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        var total = database.selectOne("SELECT count(*) AS total FROM " + TABLE + whereClause,
                PgMediaJobCountRow.class, params.toArray()).map(row -> row.total).orElse(0L);
        var pageParams = new ArrayList<>(params);
        var sql = SELECT + whereClause + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
        pageParams.add(limit);
        pageParams.add(offset);
        return new MediaJobPage(total, database.select(sql, PgMediaJobRow.class, pageParams.toArray())
            .stream().map(PgMediaJobStore::toJob).toList());
    }

    @Override
    public boolean claimArtifact(String jobId) {
        var now = ZonedDateTime.now();
        database.execute("UPDATE " + TABLE + " SET artifact_attempts = 0 WHERE id = ? AND artifact_attempts IS NULL",
            jobId);
        return database.execute("UPDATE " + TABLE + " SET artifact_state = ?, artifact_claimed_at = ?, "
                + "updated_at = ?, artifact_attempts = COALESCE(artifact_attempts, 0) + 1 "
                + "WHERE id = ? AND state = ? AND file_id IS NULL AND (media_type IS NULL OR media_type = ?) "
                + "AND (artifact_state IS NULL OR artifact_state IN (?, ?)) "
                + "AND (artifact_attempts IS NULL OR artifact_attempts < 5) "
                + "AND (artifact_next_attempt_at IS NULL OR artifact_next_attempt_at <= ?)",
            "storing", now, now, jobId, "completed", "video", "pending", "failed", now) > 0;
    }

    @Override
    public void releaseStaleClaims() {
        database.execute("UPDATE " + TABLE + " SET artifact_state = ?, artifact_claimed_at = NULL "
                + "WHERE artifact_state = ? AND artifact_claimed_at < ?",
            "pending", "storing", ZonedDateTime.now().minusMinutes(10));
    }

    @Override
    public List<MediaJob> posterCandidates(int limit) {
        var where = new ArrayList<String>();
        var params = new ArrayList<>();
        addVideoCondition(where, params);
        where.add("state = ?");
        params.add("completed");
        where.add("file_id IS NOT NULL");
        where.add("poster_file_id IS NULL");
        where.add("(poster_attempted_at IS NULL OR poster_attempted_at < ?)");
        params.add(ZonedDateTime.now().minusDays(1));
        params.add(limit);
        return database.select(SELECT + " WHERE " + String.join(" AND ", where)
                + " ORDER BY artifact_stored_at, id LIMIT ?", PgMediaJobRow.class, params.toArray())
            .stream().map(PgMediaJobStore::toJob).toList();
    }

    @Override
    public List<MediaJob> backfillCandidates(int limit) {
        var where = new ArrayList<String>();
        var params = new ArrayList<>();
        addVideoCondition(where, params);
        where.add("state = ?");
        params.add("completed");
        where.add("file_id IS NULL");
        where.add("(artifact_state IS NULL OR artifact_state IN (?, ?))");
        params.add("pending");
        params.add("failed");
        where.add("(artifact_attempts IS NULL OR artifact_attempts < 5)");
        where.add("(artifact_next_attempt_at IS NULL OR artifact_next_attempt_at <= ?)");
        params.add(ZonedDateTime.now());
        params.add(limit);
        return database.select(SELECT + " WHERE " + String.join(" AND ", where)
                + " ORDER BY completed_at, id LIMIT ?", PgMediaJobRow.class, params.toArray())
            .stream().map(PgMediaJobStore::toJob).toList();
    }

    @Override
    public List<MediaJob> staleVideoJobs(int limit) {
        var now = ZonedDateTime.now();
        var where = new ArrayList<String>();
        var params = new ArrayList<>();
        addVideoCondition(where, params);
        where.add("state IN (?, ?, ?)");
        params.add("submitted");
        params.add("queued");
        params.add("processing");
        where.add("updated_at < ?");
        params.add(now.minusMinutes(2));
        where.add("created_at >= ?");
        params.add(now.minusDays(7));
        params.add(limit);
        return database.select(SELECT + " WHERE " + String.join(" AND ", where)
                + " ORDER BY updated_at, id LIMIT ?", PgMediaJobRow.class, params.toArray())
            .stream().map(PgMediaJobStore::toJob).toList();
    }

    private void addMediaCondition(List<String> where, List<Object> params, String mediaType) {
        if ("video".equals(mediaType)) {
            where.add("(media_type IS NULL OR media_type = ?)");
            params.add("video");
        } else {
            where.add("media_type = ?");
            params.add("image");
        }
    }

    private void addVideoCondition(List<String> where, List<Object> params) {
        where.add("(media_type IS NULL OR media_type = ?)");
        params.add("video");
    }

    private Object[] params(MediaJob job) {
        return new Object[]{
            job.id,
            job.userId,
            job.sessionId,
            job.agentRunId,
            job.state,
            job.mediaType,
            job.costSource,
            job.fileId,
            job.artifactState,
            job.artifactAttempts,
            job.artifactClaimedAt,
            job.artifactNextAttemptAt,
            job.posterFileId,
            job.posterAttemptedAt,
            job.artifactStoredAt,
            job.updatedAt,
            job.createdAt,
            job.completedAt,
            JsonUtil.toJson(job)
        };
    }
}
