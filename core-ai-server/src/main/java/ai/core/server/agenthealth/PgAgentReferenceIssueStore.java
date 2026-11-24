package ai.core.server.agenthealth;

import ai.core.server.pg.PgDocumentRow;
import ai.core.server.pg.PgDocumentStore;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL implementation. The active issue uniqueness rule lives in the database:
 * (agentId, refType, refId) WHERE resolvedAt IS NULL.
 *
 * @author core-ai
 */
public class PgAgentReferenceIssueStore implements AgentReferenceIssueStore {
    private static final String TABLE = "app.agent_reference_issues";
    private static final String STATE_TABLE = "app.agent_health_state";
    private static final String STATE_ID = "reconciler";

    @Inject
    Database database;

    private PgDocumentStore<AgentReferenceIssue> store() {
        return new PgDocumentStore<>(database, TABLE, AgentReferenceIssue.class, issue -> issue.id);
    }

    @Override
    public Optional<AgentReferenceIssue> findActive(String agentId, String refType, String refId) {
        return database.selectOne("SELECT data::text AS data FROM " + TABLE
                + " WHERE data ->> ? = ? AND data ->> ? = ? AND data ->> ? = ? AND data ->> ? IS NULL",
                PgDocumentRow.class, "agentId", agentId, "refType", refType, "refId", refId, "resolvedAt")
            .map(row -> JsonUtil.fromJson(AgentReferenceIssue.class, row.data));
    }

    @Override
    @SuppressFBWarnings("CFS_CONFUSING_FUNCTION_SEMANTICS")
    public AgentReferenceIssue upsertActive(AgentReferenceIssue issue) {
        var existing = findActive(issue.agentId, issue.refType, issue.refId);
        if (existing.isPresent()) {
            var current = existing.get();
            issue.id = current.id;
            if (issue.firstDetectedAt == null) issue.firstDetectedAt = current.firstDetectedAt;
            if (issue.agentOwnerId == null) issue.agentOwnerId = current.agentOwnerId;
            if (issue.agentName == null) issue.agentName = current.agentName;
            if (issue.refName == null) issue.refName = current.refName;
            issue.notifiedAt = current.notifiedAt;
            issue.resolvedAt = null;
            issue.resolution = null;
            store().replace(issue);
            return issue;
        }

        if (issue.id == null) issue.id = UUID.randomUUID().toString();
        if (issue.firstDetectedAt == null) issue.firstDetectedAt = ZonedDateTime.now();
        try {
            store().insert(issue);
            return issue;
        } catch (RuntimeException e) {
            // another pod inserted the same active issue; re-read and update it instead of failing the batch
            var raced = findActive(issue.agentId, issue.refType, issue.refId);
            if (raced.isEmpty()) throw e;
            var current = raced.get();
            issue.id = current.id;
            issue.firstDetectedAt = current.firstDetectedAt;
            issue.notifiedAt = current.notifiedAt;
            issue.resolvedAt = null;
            issue.resolution = null;
            store().replace(issue);
            return issue;
        }
    }

    @Override
    public List<AgentReferenceIssue> listUnnotified(String agentId) {
        return database.select("SELECT data::text AS data FROM " + TABLE
                + " WHERE data ->> ? = ? AND data ->> ? IS NULL AND data ->> ? IS NULL"
                + " ORDER BY data ->> ?",
                PgDocumentRow.class, "agentId", agentId, "resolvedAt", "notifiedAt", "firstDetectedAt")
            .stream().map(row -> JsonUtil.fromJson(AgentReferenceIssue.class, row.data)).toList();
    }

    @Override
    public List<AgentReferenceIssue> listActiveByAgentIds(Collection<String> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) return List.of();
        var params = new ArrayList<Object>();
        params.add("resolvedAt");
        params.add("agentId");
        params.addAll(agentIds);
        var sql = "SELECT data::text AS data FROM " + TABLE
            + " WHERE data ->> ? IS NULL AND data ->> ? IN (" + placeholders(agentIds.size()) + ")";
        return database.select(sql, PgDocumentRow.class, params.toArray())
            .stream().map(row -> JsonUtil.fromJson(AgentReferenceIssue.class, row.data)).toList();
    }

    @Override
    public List<AgentReferenceIssue> listActive(int limit, String afterId) {
        return database.select("SELECT data::text AS data FROM " + TABLE
                + " WHERE data ->> ? IS NULL AND id > ? ORDER BY id ASC LIMIT ?",
                PgDocumentRow.class, "resolvedAt", afterId == null ? "" : afterId, Math.max(1, limit))
            .stream().map(row -> JsonUtil.fromJson(AgentReferenceIssue.class, row.data)).toList();
    }

    @Override
    public void markNotified(Collection<String> issueIds, ZonedDateTime at) {
        update(issueIds, Map.of("notifiedAt", at == null ? ZonedDateTime.now() : at));
    }

    @Override
    public void resolve(Collection<String> issueIds, String resolution, ZonedDateTime at) {
        update(issueIds, Map.of("resolvedAt", at == null ? ZonedDateTime.now() : at, "resolution", resolution));
    }

    @Override
    public void resolveByAgent(String agentId, String resolution, ZonedDateTime at) {
        var ids = database.select("SELECT id FROM " + TABLE
                + " WHERE data ->> ? = ? AND data ->> ? IS NULL",
                IdRow.class, "agentId", agentId, "resolvedAt")
            .stream().map(row -> row.id).toList();
        resolve(ids, resolution, at);
    }

    @Override
    public AgentHealthState state() {
        return database.selectOne("SELECT data::text AS data FROM " + STATE_TABLE + " WHERE id = ?",
                PgDocumentRow.class, STATE_ID)
            .map(row -> JsonUtil.fromJson(AgentHealthState.class, row.data))
            .orElseGet(() -> {
                var state = new AgentHealthState();
                state.id = STATE_ID;
                state.backfillDone = Boolean.FALSE;
                return state;
            });
    }

    @Override
    public void saveState(AgentHealthState state) {
        state.id = STATE_ID;
        database.execute("INSERT INTO " + STATE_TABLE + " (id, data, updated_at) VALUES (?, ?::jsonb, now()) "
                + "ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data, updated_at = now()",
            STATE_ID, JsonUtil.toJson(state));
    }

    private void update(Collection<String> issueIds, Map<String, Object> patch) {
        if (issueIds == null || issueIds.isEmpty()) return;
        var sql = "UPDATE " + TABLE + " SET data = data || ?::jsonb, updated_at = now() WHERE id IN ("
            + placeholders(issueIds.size()) + ")";
        var params = new ArrayList<Object>();
        params.add(JsonUtil.toJson(patch));
        params.addAll(issueIds);
        database.execute(sql, params.toArray());
    }

    private String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    public static class IdRow {
        @core.framework.db.Column(name = "id")
        public String id;
    }
}
