package ai.core.server.dataset.migration;

import ai.core.server.dataset.store.PgBoolRow;
import ai.core.server.dataset.store.PgDatasetMetaStore;
import ai.core.server.dataset.store.PostgresDatasetRecordStore;
import ai.core.server.domain.Dataset;
import ai.core.server.domain.DatasetRecord;
import ai.core.utils.JsonUtil;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.db.Database;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * One-way copy from the Mongo dataset collections to PostgreSQL. Metadata and records are handled
 * separately, record upserts are idempotent (last write wins by updated_at), and a final pass removes
 * rows that no longer exist in Mongo. Invalid record JSON is skipped and reported instead of aborting.
 *
 * @author stephen
 */
public class DatasetMigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatasetMigrationService.class);

    private static final int DATASET_PAGE = 200;
    private static final int RECORD_PAGE = 1000;
    private static final String LOCK_KEY = "dataset_migration";

    private static Query page(String afterId, int limit) {
        var query = new Query();
        query.filter = Filters.gt("_id", afterId);
        query.sort = Sorts.ascending("_id");
        query.limit = limit;
        return query;
    }

    @Inject
    MongoCollection<Dataset> datasetCollection;

    @Inject
    MongoCollection<DatasetRecord> datasetRecordCollection;

    @Inject
    PgDatasetMetaStore metaStore;

    @Inject
    PostgresDatasetRecordStore recordStore;

    @Inject
    Database database;

    public Report migrate() {
        try (var transaction = database.beginTransaction()) {
            var lock = database.selectOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS acquired", PgBoolRow.class, LOCK_KEY)
                    .orElse(null);
            if (lock == null || lock.acquired == null || !lock.acquired) {
                transaction.rollback();
                LOGGER.info("dataset migration skipped, another instance holds the lock");
                return new Report(0, 0, 0, 0, List.of("another instance is migrating"));
            }
            var report = run();
            transaction.commit();
            return report;
        }
    }

    private Report run() {
        long started = System.currentTimeMillis();
        int datasets = migrateDatasets();
        var recordResult = migrateRecords();
        var reconciliation = reconcile();
        var report = new Report(datasets, recordResult.count(), recordResult.skipped(), reconciliation.deleted(), reconciliation.mismatches());
        LOGGER.info("dataset migration finished, datasets={}, records={}, skipped={}, deleted={}, mismatches={}, elapsed={}ms",
                report.datasets(), report.records(), report.skipped(), report.deleted(), report.mismatches(),
                System.currentTimeMillis() - started);
        return report;
    }

    private int migrateDatasets() {
        int count = 0;
        String lastId = "";
        while (true) {
            var page = datasetCollection.find(page(lastId, DATASET_PAGE));
            if (page.isEmpty()) return count;
            for (var dataset : page) {
                lastId = dataset.id;
                if (metaStore.get(dataset.id) == null) {
                    metaStore.create(dataset);
                } else {
                    metaStore.update(dataset);
                }
                recordStore.onDatasetSchemaChanged(dataset);
                count++;
            }
        }
    }

    private RecordResult migrateRecords() {
        int count = 0;
        int skipped = 0;
        String lastId = "";
        while (true) {
            var page = datasetRecordCollection.find(page(lastId, RECORD_PAGE));
            if (page.isEmpty()) return new RecordResult(count, skipped);
            for (var record : page) {
                lastId = record.id;
                if (!validData(record)) {
                    skipped++;
                    continue;
                }
                recordStore.upsert(record);
                count++;
            }
        }
    }

    private boolean validData(DatasetRecord record) {
        if (record.data == null || record.data.isBlank()) return true;
        try {
            JsonUtil.toMap(record.data);
            return true;
        } catch (RuntimeException | Error e) {
            LOGGER.warn("skip dataset record with invalid json, id={}, datasetId={}, error={}",
                    record.id, record.datasetId, e.getMessage());
            return false;
        }
    }

    private Reconciliation reconcile() {
        int deleted = 0;
        var mismatches = new ArrayList<String>();
        int offset = 0;
        while (true) {
            var page = metaStore.list(null, offset, 100);
            if (page.isEmpty()) return new Reconciliation(deleted, mismatches);
            for (var dataset : page) {
                long mongoCount = datasetRecordCollection.count(Filters.eq("dataset_id", dataset.id));
                long pgCount = recordStore.countByDataset(dataset.id);
                if (mongoCount == pgCount) continue;
                if (mongoCount < pgCount) {
                    deleted += deleteExtras(dataset.id);
                } else {
                    mismatches.add(dataset.id + ": mongo=" + mongoCount + ", pg=" + pgCount);
                }
            }
            offset += page.size();
        }
    }

    private int deleteExtras(String datasetId) {
        var mongoIds = new HashSet<String>();
        datasetRecordCollection.find(Filters.eq("dataset_id", datasetId)).forEach(record -> mongoIds.add(record.id));
        int deleted = 0;
        for (var id : recordStore.recordIds(datasetId)) {
            if (!mongoIds.contains(id)) {
                recordStore.delete(datasetId, id);
                deleted++;
            }
        }
        if (deleted > 0) LOGGER.info("deleted records missing from mongo, datasetId={}, count={}", datasetId, deleted);
        return deleted;
    }

    private record RecordResult(int count, int skipped) {
    }

    private record Reconciliation(int deleted, List<String> mismatches) {
    }

    public record Report(int datasets, int records, int skipped, int deleted, List<String> mismatches) {
    }
}
