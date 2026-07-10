package ai.core.server.dataset.tool;

import ai.core.server.dataset.DatasetService;
import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.DatasetPermission;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public record DatasetAccessRegistry(Map<String, DatasetPermission> allowedDatasets, Map<String, DatasetPermission> nameIndex) {
    public DatasetAccessRegistry(Map<String, DatasetPermission> allowedDatasets) {
        this(allowedDatasets, Map.of());
    }

    public DatasetAccessRegistry(Map<String, DatasetPermission> allowedDatasets, Map<String, DatasetPermission> nameIndex) {
        this.allowedDatasets = Collections.unmodifiableMap(allowedDatasets);
        this.nameIndex = Collections.unmodifiableMap(nameIndex);
    }

    public static DatasetAccessRegistry from(List<AgentDatasetConfig> configs) {
        return from(configs, null);
    }

    public static DatasetAccessRegistry from(List<AgentDatasetConfig> configs, DatasetService datasetService) {
        if (configs == null || configs.isEmpty()) {
            return new DatasetAccessRegistry(Map.of());
        }
        var uuidMap = configs.stream()
                .collect(Collectors.toMap(c -> c.datasetId, c -> c.permission, (a, b) -> a));

        var names = new HashMap<String, DatasetPermission>();
        if (datasetService != null) {
            for (var config : configs) {
                var dataset = datasetService.get(config.datasetId);
                if (dataset != null && dataset.name != null && !dataset.name.isBlank()) {
                    names.put(dataset.name, config.permission);
                }
            }
        }

        return new DatasetAccessRegistry(uuidMap, names);
    }

    public DatasetPermission resolve(String datasetId) {
        var perm = allowedDatasets.get(datasetId);
        if (perm != null) return perm;
        return nameIndex.get(datasetId);
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
        var perm = resolve(datasetId);
        return perm == DatasetPermission.WRITE || perm == DatasetPermission.FULL;
    }

    public boolean isDeletable(String datasetId) {
        return resolve(datasetId) == DatasetPermission.FULL;
    }
}
