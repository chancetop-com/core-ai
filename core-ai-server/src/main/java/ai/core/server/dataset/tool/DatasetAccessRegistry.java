package ai.core.server.dataset.tool;

import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.DatasetPermission;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public record DatasetAccessRegistry(Map<String, DatasetPermission> allowedDatasets) {
    public DatasetAccessRegistry(Map<String, DatasetPermission> allowedDatasets) {
        this.allowedDatasets = Collections.unmodifiableMap(allowedDatasets);
    }

    public static DatasetAccessRegistry from(List<AgentDatasetConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            return new DatasetAccessRegistry(Map.of());
        }
        var map = configs.stream()
                .collect(Collectors.toMap(c -> c.datasetId, c -> c.permission, (a, b) -> a));
        return new DatasetAccessRegistry(map);
    }

    public DatasetPermission resolve(String datasetId) {
        return allowedDatasets.get(datasetId);
    }

    public boolean isEmpty() {
        return allowedDatasets.isEmpty();
    }

    public boolean hasAnyWrite() {
        return allowedDatasets.values().stream()
                .anyMatch(p -> p == DatasetPermission.WRITE || p == DatasetPermission.FULL);
    }

    public boolean hasAnyFull() {
        return allowedDatasets.values().stream()
                .anyMatch(p -> p == DatasetPermission.FULL);
    }

    public boolean isWritable(String datasetId) {
        var perm = allowedDatasets.get(datasetId);
        return perm == DatasetPermission.WRITE || perm == DatasetPermission.FULL;
    }

    public boolean isDeletable(String datasetId) {
        return allowedDatasets.get(datasetId) == DatasetPermission.FULL;
    }
}
