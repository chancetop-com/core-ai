package ai.core.server.workspace;

import ai.core.sandbox.SandboxConstants;
import core.framework.api.json.Property;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Samples filesystem usage from a READY workspace runtime via {@code GET /workspace/stat} and
 * persists the latest snapshot so reads never block on a live runtime probe. Sampling is best-effort
 * and never acquires a turn lease.
 *
 * @author stephen
 */
public class WorkspaceUsageService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceUsageService.class);

    private static StatClient defaultStatClient() {
        var httpClient = HTTPClient.builder()
                .connectTimeout(Duration.ofSeconds(2))
                .timeout(Duration.ofSeconds(5))
                .build();
        return (host, port) -> {
            var url = "http://" + host + ":" + port + "/workspace/stat";
            var response = httpClient.execute(new HTTPRequest(HTTPMethod.GET, url));
            // A READY pod still running an older runtime image has no /workspace/stat yet; skip quietly
            // and let a later sample succeed once the pod is replaced.
            if (response.statusCode == 404) return null;
            if (response.statusCode != 200) {
                throw new RuntimeException("runtime stat failed: status=" + response.statusCode + ", body=" + response.text());
            }
            return JSON.fromJSON(RuntimeStatResponse.class, response.text());
        };
    }

    private final WorkspaceStore workspaces;
    private final WorkspaceBindingStore bindings;
    private final WorkspaceUsageStore usageStore;
    private final WorkspaceRuntimeManager runtimes;
    private final StatClient statClient;

    public WorkspaceUsageService(WorkspaceStore workspaces, WorkspaceBindingStore bindings,
                                 WorkspaceUsageStore usageStore, WorkspaceRuntimeManager runtimes) {
        this(workspaces, bindings, usageStore, runtimes,
                runtimes != null && runtimes.available() ? defaultStatClient() : null);
    }

    WorkspaceUsageService(WorkspaceStore workspaces, WorkspaceBindingStore bindings,
                          WorkspaceUsageStore usageStore, WorkspaceRuntimeManager runtimes,
                          StatClient statClient) {
        this.workspaces = workspaces;
        this.bindings = bindings;
        this.usageStore = usageStore;
        this.runtimes = runtimes;
        this.statClient = statClient;
    }

    public boolean sample(String workspaceId) {
        if (workspaceId == null || usageStore == null || workspaces == null || bindings == null
                || runtimes == null || statClient == null) {
            return false;
        }
        try {
            var workspace = workspaces.get(workspaceId).orElse(null);
            if (workspace == null || workspace.deleted() || workspace.runtimeStatus != WorkspaceRuntimeStatus.READY) {
                return false;
            }
            var binding = bindings.get(workspaceId).orElse(null);
            if (binding == null || binding.sandboxId == null || binding.sandboxId.isBlank()) {
                return false;
            }
            var host = runtimes.endpointHost(binding.sandboxId).orElse(null);
            if (host == null) {
                LOGGER.warn("workspace usage sample skipped: runtime endpoint unavailable, workspace={}, runtime={}",
                        workspaceId, binding.sandboxId);
                return false;
            }
            var stat = statClient.stat(host, SandboxConstants.RUNTIME_PORT);
            if (stat == null) return false;
            var record = new WorkspaceUsageRecord();
            record.workspaceId = workspaceId;
            record.usedBytes = stat.usedBytes;
            record.capacityBytes = stat.capacityBytes;
            record.availableBytes = stat.availableBytes;
            record.inodesUsed = stat.inodesUsed;
            record.inodesTotal = stat.inodesTotal;
            record.volumeGeneration = workspace.activeVolumeGeneration;
            record.sampledAt = ZonedDateTime.now();
            usageStore.upsert(record);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("failed to sample workspace usage, workspace={}", workspaceId, e);
            return false;
        }
    }

    public Optional<WorkspaceUsageRecord> read(String workspaceId) {
        if (usageStore == null || workspaceId == null) return Optional.empty();
        return usageStore.get(workspaceId);
    }

    public Map<String, WorkspaceUsageRecord> readAll(List<String> workspaceIds) {
        if (usageStore == null || workspaceIds == null || workspaceIds.isEmpty()) return Map.of();
        return usageStore.listByWorkspaces(workspaceIds);
    }

    public void delete(String workspaceId) {
        if (usageStore == null || workspaceId == null) return;
        usageStore.delete(workspaceId);
    }

    @FunctionalInterface
    interface StatClient {
        RuntimeStatResponse stat(String host, int port);
    }

    public static class RuntimeStatResponse {
        @Property(name = "capacity_bytes")
        public Long capacityBytes;

        @Property(name = "used_bytes")
        public Long usedBytes;

        @Property(name = "available_bytes")
        public Long availableBytes;

        @Property(name = "inodes_total")
        public Long inodesTotal;

        @Property(name = "inodes_used")
        public Long inodesUsed;
    }
}
