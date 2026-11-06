package ai.core.server.dataset.store;

import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.domain.Dataset;
import ai.core.server.domain.DatasetRecord;

import java.util.Map;
import java.util.Optional;

/**
 * Storage contract for dataset records, so the same service surface can run on core-ng's Mongo mapping,
 * on the native Mongo driver, or on PostgreSQL. The store owns everything that depends on the physical
 * layout, including the per-dataset view lifecycle.
 *
 * @author stephen
 */
public interface DatasetRecordStore {
    void insert(DatasetRecord record);

    DatasetRecordService.QueryResult query(DatasetRecordService.QueryRequest request);

    boolean update(String datasetId, String id, Map<String, Object> patch, String updatedBy);

    boolean delete(String datasetId, String id);

    long deleteByDataset(String datasetId);

    Optional<DatasetRecord> findBySession(String datasetId, String sessionId);

    void upsertBySession(String datasetId, String sessionId, String dataJson, String agentId, String userId);

    void patchBySession(String datasetId, String sessionId, String patchJson, String agentId, String userId);

    // stores without a database-side aggregation raise UnsupportedOperationException
    AggregateResult aggregate(AggregateRequest request);

    // renames a top-level field in every record of the dataset; returns the number of records changed
    default long renameField(String datasetId, String from, String to, String updatedBy) {
        throw new UnsupportedOperationException("rename_field is not supported by this dataset store");
    }

    // hook for stores that materialize the schema (postgres views); no-op elsewhere
    default void onDatasetSchemaChanged(Dataset dataset) {
    }

    default void onDatasetDeleted(String datasetId) {
    }
}
