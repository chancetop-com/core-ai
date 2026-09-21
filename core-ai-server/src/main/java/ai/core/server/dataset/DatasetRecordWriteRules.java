package ai.core.server.dataset;

import ai.core.server.domain.Dataset;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rules shared by the dataset record write paths: the agent tools and the hub dataset service must
 * store exactly the same fields, otherwise the two access surfaces drift apart.
 *
 * @author stephen
 */
public final class DatasetRecordWriteRules {
    /**
     * Keeps only the fields declared in the dataset schema; a dataset without schema accepts every field.
     * Null values are dropped, matching the stored shape of a record (no explicit null fields).
     */
    public static Map<String, Object> filterToSchema(Dataset dataset, Map<String, Object> data) {
        if (dataset == null || dataset.schema == null || dataset.schema.isEmpty()) return new LinkedHashMap<>(data);
        var filtered = new LinkedHashMap<String, Object>();
        for (var field : dataset.schema) {
            var value = data.get(field.name);
            if (value != null) filtered.put(field.name, value);
        }
        return filtered;
    }

    public static List<String> schemaFieldNames(Dataset dataset) {
        return dataset != null && dataset.schema != null
                ? dataset.schema.stream().map(f -> f.name).toList() : List.of();
    }

    private DatasetRecordWriteRules() {
    }
}
