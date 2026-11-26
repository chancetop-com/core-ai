package ai.core.server.workspace;

import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgWorkspaceBindingStore implements WorkspaceBindingStore {
    private static final String TABLE = "app.workspace_bindings";
    private static final String SELECT = "SELECT workspace_id, sandbox_id, provider, runtime_generation, pod_id, "
            + "bound_session_id, runtime_started_at, bound_at, updated_at FROM " + TABLE;

    private static WorkspaceBindingRecord toBinding(PgWorkspaceBindingRow row) {
        var binding = new WorkspaceBindingRecord();
        binding.workspaceId = row.workspaceId;
        binding.sandboxId = row.sandboxId;
        binding.provider = row.provider;
        binding.runtimeGeneration = row.runtimeGeneration;
        binding.podId = row.podId;
        binding.boundSessionId = row.boundSessionId;
        binding.runtimeStartedAt = row.runtimeStartedAt;
        binding.boundAt = row.boundAt;
        binding.updatedAt = row.updatedAt;
        return binding;
    }

    @Inject
    Database database;

    @Override
    public Optional<WorkspaceBindingRecord> get(String workspaceId) {
        return database.selectOne(SELECT + " WHERE workspace_id = ?", PgWorkspaceBindingRow.class, workspaceId)
                .map(PgWorkspaceBindingStore::toBinding);
    }

    @Override
    public void upsert(WorkspaceBindingRecord binding) {
        database.execute("INSERT INTO " + TABLE + " (workspace_id, sandbox_id, provider, runtime_generation, pod_id, "
                        + "bound_session_id, runtime_started_at, bound_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (workspace_id) DO UPDATE SET sandbox_id = EXCLUDED.sandbox_id, "
                        + "provider = EXCLUDED.provider, runtime_generation = EXCLUDED.runtime_generation, "
                        + "pod_id = EXCLUDED.pod_id, bound_session_id = EXCLUDED.bound_session_id, "
                        + "runtime_started_at = EXCLUDED.runtime_started_at, bound_at = EXCLUDED.bound_at, "
                        + "updated_at = EXCLUDED.updated_at",
                binding.workspaceId, binding.sandboxId, binding.provider, binding.runtimeGeneration, binding.podId,
                binding.boundSessionId, binding.runtimeStartedAt, binding.boundAt, binding.updatedAt);
    }

    @Override
    public boolean clearSessionIfBound(String workspaceId, String sessionId, ZonedDateTime at) {
        return database.execute("UPDATE " + TABLE + " SET bound_session_id = NULL, updated_at = ? "
                + "WHERE workspace_id = ? AND bound_session_id = ?", at, workspaceId, sessionId) > 0;
    }

    @Override
    public boolean rebindSession(String workspaceId, String sandboxId, long runtimeGeneration, String sessionId, ZonedDateTime at) {
        return database.execute("UPDATE " + TABLE + " SET bound_session_id = ?, bound_at = ?, updated_at = ? "
                        + "WHERE workspace_id = ? AND sandbox_id = ? AND runtime_generation = ?",
                sessionId, at, at, workspaceId, sandboxId, runtimeGeneration) > 0;
    }

    @Override
    public void delete(String workspaceId) {
        database.execute("DELETE FROM " + TABLE + " WHERE workspace_id = ?", workspaceId);
    }
}
