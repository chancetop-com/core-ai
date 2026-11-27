package ai.core.server.workspace;

import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgWorkspaceUsageStore implements WorkspaceUsageStore {
    private static final String TABLE = "app.workspace_usage";
    private static final String SELECT = "SELECT workspace_id, used_bytes, capacity_bytes, available_bytes, "
            + "inodes_used, inodes_total, volume_generation, sampled_at FROM " + TABLE;

    private static WorkspaceUsageRecord toUsage(PgWorkspaceUsageRow row) {
        var usage = new WorkspaceUsageRecord();
        usage.workspaceId = row.workspaceId;
        usage.usedBytes = row.usedBytes;
        usage.capacityBytes = row.capacityBytes;
        usage.availableBytes = row.availableBytes;
        usage.inodesUsed = row.inodesUsed;
        usage.inodesTotal = row.inodesTotal;
        usage.volumeGeneration = row.volumeGeneration;
        usage.sampledAt = row.sampledAt;
        return usage;
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    @Inject
    Database database;

    @Override
    public Optional<WorkspaceUsageRecord> get(String workspaceId) {
        return database.selectOne(SELECT + " WHERE workspace_id = ?", PgWorkspaceUsageRow.class, workspaceId)
                .map(PgWorkspaceUsageStore::toUsage);
    }

    @Override
    public Map<String, WorkspaceUsageRecord> listByWorkspaces(List<String> workspaceIds) {
        if (workspaceIds == null || workspaceIds.isEmpty()) return Map.of();
        var rows = database.select(SELECT + " WHERE workspace_id IN (" + placeholders(workspaceIds.size()) + ")",
                PgWorkspaceUsageRow.class, workspaceIds.toArray());
        var result = new LinkedHashMap<String, WorkspaceUsageRecord>(rows.size());
        for (var row : rows) {
            result.put(row.workspaceId, toUsage(row));
        }
        return result;
    }

    @Override
    public void upsert(WorkspaceUsageRecord usage) {
        database.execute("INSERT INTO " + TABLE + " (workspace_id, used_bytes, capacity_bytes, available_bytes, "
                        + "inodes_used, inodes_total, volume_generation, sampled_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (workspace_id) DO UPDATE SET used_bytes = EXCLUDED.used_bytes, "
                        + "capacity_bytes = EXCLUDED.capacity_bytes, available_bytes = EXCLUDED.available_bytes, "
                        + "inodes_used = EXCLUDED.inodes_used, inodes_total = EXCLUDED.inodes_total, "
                        + "volume_generation = EXCLUDED.volume_generation, sampled_at = EXCLUDED.sampled_at",
                usage.workspaceId, usage.usedBytes, usage.capacityBytes, usage.availableBytes,
                usage.inodesUsed, usage.inodesTotal, usage.volumeGeneration, usage.sampledAt);
    }

    @Override
    public void delete(String workspaceId) {
        database.execute("DELETE FROM " + TABLE + " WHERE workspace_id = ?", workspaceId);
    }
}
