package ai.core.server.dataset.tool;

import ai.core.server.dataset.DatasetService;
import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.DatasetPermission;
import ai.core.server.domain.DatasetType;
import ai.core.server.domain.SchemaField;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The dataset bindings of one session/agent: dataset id -> permission, plus the name index used to
 * address a binding by name. A name bound more than once inside the same bindings stays unresolved
 * (fail closed) — callers must pass the dataset id.
 *
 * @author stephen
 */
public record DatasetAccessRegistry(Map<String, DatasetPermission> allowedDatasets, Map<String, List<String>> nameIndex) {
    public DatasetAccessRegistry(Map<String, DatasetPermission> allowedDatasets) {
        this(allowedDatasets, Map.of());
    }

    public DatasetAccessRegistry(Map<String, DatasetPermission> allowedDatasets, Map<String, List<String>> nameIndex) {
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

        var names = new HashMap<String, List<String>>();
        if (datasetService != null) {
            for (var config : configs) {
                var dataset = datasetService.get(config.datasetId);
                if (dataset != null && dataset.name != null && !dataset.name.isBlank()) {
                    names.computeIfAbsent(dataset.name, name -> new ArrayList<>()).add(config.datasetId);
                }
            }
        }
        var nameIndex = new HashMap<String, List<String>>();
        for (var entry : names.entrySet()) {
            nameIndex.put(entry.getKey(), entry.getValue().stream().distinct().sorted().toList());
        }

        return new DatasetAccessRegistry(uuidMap, nameIndex);
    }

    /**
     * Canonical dataset id behind a caller reference (dataset id, or a name unique within these bindings).
     * Null when the reference is unknown or ambiguous.
     */
    public String resolveId(String datasetRef) {
        if (datasetRef == null) return null;
        if (allowedDatasets.containsKey(datasetRef)) return datasetRef;
        var ids = nameIndex.get(datasetRef);
        return ids != null && ids.size() == 1 ? ids.get(0) : null;
    }

    /** Dataset ids bound to the given name; more than one means the name cannot be used. */
    public List<String> candidates(String datasetRef) {
        if (datasetRef == null || allowedDatasets.containsKey(datasetRef)) return List.of();
        return nameIndex.getOrDefault(datasetRef, List.of());
    }

    public boolean isAmbiguous(String datasetRef) {
        return candidates(datasetRef).size() > 1;
    }

    /** Failure message for an ambiguous name, null when the reference is not ambiguous. */
    public String ambiguousMessage(String datasetRef) {
        if (!isAmbiguous(datasetRef)) return null;
        return "dataset name is ambiguous, pass the dataset id: " + datasetRef
                + " (candidates: " + String.join(", ", candidates(datasetRef)) + ")";
    }

    public DatasetPermission resolve(String datasetRef) {
        var datasetId = resolveId(datasetRef);
        return datasetId == null ? null : allowedDatasets.get(datasetId);
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

    public boolean isWritable(String datasetRef) {
        var permission = resolve(datasetRef);
        return permission == DatasetPermission.WRITE || permission == DatasetPermission.FULL;
    }

    public boolean isDeletable(String datasetRef) {
        return resolve(datasetRef) == DatasetPermission.FULL;
    }

    /**
     * The bindings as they are offered to scripts: existing datasets only, ordered by name then id so every
     * surface (catalog section, hub list) renders the same list. Datasets deleted after the session snapshot
     * was taken stay allowed but are not offered — a call still fails with {@code dataset not found}.
     */
    public List<Binding> bindings(DatasetService datasetService) {
        var list = new ArrayList<Binding>();
        for (var entry : allowedDatasets.entrySet()) {
            var dataset = datasetService.get(entry.getKey());
            if (dataset == null) continue;
            list.add(new Binding(entry.getKey(), dataset.name, DatasetService.resolveType(dataset), entry.getValue(),
                    dataset.description, dataset.schema != null ? dataset.schema : List.of()));
        }
        list.sort(Comparator.comparing(Binding::name, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Binding::datasetId));
        return list;
    }

    /** One dataset a session may reach, with the permission that session holds on it. */
    public record Binding(String datasetId, String name, DatasetType type, DatasetPermission permission,
                          String description, List<SchemaField> schema) {
    }
}
