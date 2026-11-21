package ai.core.server.evaluation.store;

import ai.core.server.evaluation.domain.CandidateSnapshotEntity;
import ai.core.server.evaluation.domain.DatasetRevisionEntity;
import ai.core.server.evaluation.domain.EvalCaseEntity;
import ai.core.server.evaluation.domain.EvalSpecEntity;
import ai.core.server.evaluation.domain.EvaluationEntity;
import ai.core.server.evaluation.domain.GraderSuiteEntity;
import ai.core.server.util.JsonSanitizer;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgEvaluationStore implements EvaluationStore {
    private static final String EVALUATION_TABLE = "app.eval_evaluations";
    private static final String EVALUATION_SELECT = "SELECT id, owner_id, agent_id, name, description, baseline_run_id, "
        + "baseline_generation, archived_at, created_at, updated_at, data::text AS data FROM " + EVALUATION_TABLE;

    private static final String DATASET_TABLE = "app.eval_dataset_revisions";
    private static final String DATASET_SELECT = "SELECT id, evaluation_id, revision, status, manifest_json::text AS manifest_json, "
        + "content_hash, case_count, created_by, created_at, data::text AS data FROM " + DATASET_TABLE;

    private static final String CASE_TABLE = "app.eval_cases";
    private static final String CASE_SELECT = "SELECT id, dataset_revision_id, case_id, case_hash, split, weight, "
        + "body_json::text AS body_json FROM " + CASE_TABLE;

    private static final String SUITE_TABLE = "app.eval_grader_suites";
    private static final String SUITE_SELECT = "SELECT id, evaluation_id, revision, name, fingerprint, body_json::text AS body_json, "
        + "test_receipt_json::text AS test_receipt_json, created_by, created_at, data::text AS data FROM " + SUITE_TABLE;

    private static final String SPEC_TABLE = "app.eval_spec_revisions";
    private static final String SPEC_SELECT = "SELECT id, evaluation_id, revision, dataset_revision_id, grader_suite_id, fingerprint, "
        + "body_json::text AS body_json, created_by, created_at, data::text AS data FROM " + SPEC_TABLE;

    private static final String SNAPSHOT_TABLE = "app.eval_candidate_snapshots";
    private static final String SNAPSHOT_SELECT = "SELECT id, evaluation_id, agent_id, source, source_revision, fingerprint, "
        + "snapshot_json::text AS snapshot_json, created_by, created_at, data::text AS data FROM " + SNAPSHOT_TABLE;

    private static EvaluationEntity toEvaluation(PgEvaluationRow row) {
        var entity = JsonUtil.fromJson(EvaluationEntity.class, row.data);
        entity.id = row.id;
        entity.ownerId = row.ownerId;
        entity.agentId = row.agentId;
        entity.name = row.name;
        entity.description = row.description;
        entity.baselineRunId = row.baselineRunId;
        entity.baselineGeneration = row.baselineGeneration;
        entity.archivedAt = row.archivedAt;
        entity.createdAt = row.createdAt;
        entity.updatedAt = row.updatedAt;
        return entity;
    }

    private static DatasetRevisionEntity toDataset(PgDatasetRevisionRow row) {
        var entity = JsonUtil.fromJson(DatasetRevisionEntity.class, row.data);
        entity.id = row.id;
        entity.evaluationId = row.evaluationId;
        entity.revision = row.revision;
        entity.status = row.status;
        entity.manifestJson = row.manifestJson;
        entity.contentHash = row.contentHash;
        entity.caseCount = row.caseCount;
        entity.createdBy = row.createdBy;
        entity.createdAt = row.createdAt;
        return entity;
    }

    private static EvalCaseEntity toCase(PgEvalCaseRow row) {
        var entity = new EvalCaseEntity();
        entity.id = row.id;
        entity.datasetRevisionId = row.datasetRevisionId;
        entity.caseId = row.caseId;
        entity.caseHash = row.caseHash;
        entity.split = row.split;
        entity.weight = row.weight;
        entity.bodyJson = row.bodyJson;
        return entity;
    }

    private static GraderSuiteEntity toSuite(PgGraderSuiteRow row) {
        var entity = JsonUtil.fromJson(GraderSuiteEntity.class, row.data);
        entity.id = row.id;
        entity.evaluationId = row.evaluationId;
        entity.revision = row.revision;
        entity.name = row.name;
        entity.fingerprint = row.fingerprint;
        entity.bodyJson = row.bodyJson;
        entity.testReceiptJson = row.testReceiptJson;
        entity.createdBy = row.createdBy;
        entity.createdAt = row.createdAt;
        return entity;
    }

    private static EvalSpecEntity toSpec(PgSpecRow row) {
        var entity = JsonUtil.fromJson(EvalSpecEntity.class, row.data);
        entity.id = row.id;
        entity.evaluationId = row.evaluationId;
        entity.revision = row.revision;
        entity.datasetRevisionId = row.datasetRevisionId;
        entity.graderSuiteId = row.graderSuiteId;
        entity.fingerprint = row.fingerprint;
        entity.bodyJson = row.bodyJson;
        entity.createdBy = row.createdBy;
        entity.createdAt = row.createdAt;
        return entity;
    }

    private static CandidateSnapshotEntity toSnapshot(PgSnapshotRow row) {
        var entity = JsonUtil.fromJson(CandidateSnapshotEntity.class, row.data);
        entity.id = row.id;
        entity.evaluationId = row.evaluationId;
        entity.agentId = row.agentId;
        entity.source = row.source;
        entity.sourceRevision = row.sourceRevision;
        entity.fingerprint = row.fingerprint;
        entity.snapshotJson = row.snapshotJson;
        entity.createdBy = row.createdBy;
        entity.createdAt = row.createdAt;
        return entity;
    }

    @Inject
    public Database database;

    @Override
    public Optional<EvaluationEntity> getEvaluation(String id) {
        return database.selectOne(EVALUATION_SELECT + " WHERE id = ?", PgEvaluationRow.class, id)
            .map(PgEvaluationStore::toEvaluation);
    }

    @Override
    public void insertEvaluation(EvaluationEntity evaluation) {
        database.execute("INSERT INTO " + EVALUATION_TABLE + " (id, owner_id, agent_id, name, description, baseline_run_id, "
                + "baseline_generation, archived_at, created_at, updated_at, data) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
            evaluation.id, evaluation.ownerId, evaluation.agentId, evaluation.name, evaluation.description,
            evaluation.baselineRunId, evaluation.baselineGeneration, evaluation.archivedAt, evaluation.createdAt,
            evaluation.updatedAt, JsonSanitizer.json(JsonUtil.toJson(evaluation)));
    }

    @Override
    public boolean replaceEvaluation(EvaluationEntity evaluation) {
        return database.execute("UPDATE " + EVALUATION_TABLE + " SET name = ?, description = ?, baseline_run_id = ?, "
                + "baseline_generation = ?, archived_at = ?, updated_at = ?, data = ?::jsonb WHERE id = ?",
            evaluation.name, evaluation.description, evaluation.baselineRunId, evaluation.baselineGeneration,
            evaluation.archivedAt, evaluation.updatedAt, JsonSanitizer.json(JsonUtil.toJson(evaluation)), evaluation.id) > 0;
    }

    @Override
    public List<EvaluationEntity> listEvaluations(String ownerId, boolean all, String agentId, int offset, int limit) {
        var sql = new StringBuilder(1024).append(EVALUATION_SELECT).append(" WHERE archived_at IS NULL");
        var params = new ArrayList<>();
        if (!all) {
            sql.append(" AND owner_id = ?");
            params.add(ownerId);
        }
        if (agentId != null && !agentId.isBlank()) {
            sql.append(" AND agent_id = ?");
            params.add(agentId);
        }
        sql.append(" ORDER BY updated_at DESC NULLS LAST, id DESC LIMIT ? OFFSET ?");
        params.add(Math.clamp(limit, 1, 200));
        params.add(Math.max(offset, 0));
        return database.select(sql.toString(), PgEvaluationRow.class, params.toArray()).stream()
            .map(PgEvaluationStore::toEvaluation).toList();
    }

    @Override
    public long countEvaluations(String ownerId, boolean all, String agentId) {
        var sql = new StringBuilder(256).append("SELECT count(*) AS total FROM ").append(EVALUATION_TABLE).append(" WHERE archived_at IS NULL");
        var params = new ArrayList<>();
        if (!all) {
            sql.append(" AND owner_id = ?");
            params.add(ownerId);
        }
        if (agentId != null && !agentId.isBlank()) {
            sql.append(" AND agent_id = ?");
            params.add(agentId);
        }
        return database.selectOne(sql.toString(), PgEvalCountRow.class, params.toArray()).map(row -> row.total).orElse(0L);
    }

    @Override
    public boolean archiveEvaluation(String id) {
        return database.execute("UPDATE " + EVALUATION_TABLE + " SET archived_at = now(), updated_at = now() "
            + "WHERE id = ? AND archived_at IS NULL", id) > 0;
    }

    @Override
    public int nextDatasetRevision(String evaluationId) {
        return database.selectOne("SELECT COALESCE(MAX(revision), 0) + 1 AS next_revision FROM " + DATASET_TABLE
            + " WHERE evaluation_id = ?", PgEvalNextRow.class, evaluationId).map(row -> row.nextRevision).orElse(1);
    }

    @Override
    public void insertDatasetRevision(DatasetRevisionEntity revision) {
        database.execute("INSERT INTO " + DATASET_TABLE + " (id, evaluation_id, revision, status, manifest_json, content_hash, "
                + "case_count, created_by, created_at, data) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb)",
            revision.id, revision.evaluationId, revision.revision, revision.status, JsonSanitizer.json(revision.manifestJson),
            revision.contentHash, revision.caseCount, revision.createdBy, revision.createdAt,
            JsonSanitizer.json(JsonUtil.toJson(revision)));
    }

    @Override
    public Optional<DatasetRevisionEntity> getDatasetRevision(String evaluationId, int revision) {
        return database.selectOne(DATASET_SELECT + " WHERE evaluation_id = ? AND revision = ?",
            PgDatasetRevisionRow.class, evaluationId, revision).map(PgEvaluationStore::toDataset);
    }

    @Override
    public Optional<DatasetRevisionEntity> getDatasetRevisionById(String id) {
        return database.selectOne(DATASET_SELECT + " WHERE id = ?", PgDatasetRevisionRow.class, id)
            .map(PgEvaluationStore::toDataset);
    }

    @Override
    public List<DatasetRevisionEntity> listDatasetRevisions(String evaluationId) {
        return database.select(DATASET_SELECT + " WHERE evaluation_id = ? ORDER BY revision DESC",
                PgDatasetRevisionRow.class, evaluationId).stream().map(PgEvaluationStore::toDataset).toList();
    }

    @Override
    public void insertCases(List<EvalCaseEntity> cases) {
        for (var item : cases) {
            database.execute("INSERT INTO " + CASE_TABLE + " (id, dataset_revision_id, case_id, case_hash, split, weight, "
                    + "body_json) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)",
                item.id, item.datasetRevisionId, item.caseId, item.caseHash, item.split, item.weight,
                JsonSanitizer.json(item.bodyJson));
        }
    }

    @Override
    public List<EvalCaseEntity> listCases(String datasetRevisionId, String split, int offset, int limit) {
        var sql = new StringBuilder(512).append(CASE_SELECT).append(" WHERE dataset_revision_id = ?");
        var params = new ArrayList<>();
        params.add(datasetRevisionId);
        if (split != null && !split.isBlank()) {
            sql.append(" AND split = ?");
            params.add(split);
        }
        sql.append(" ORDER BY case_id LIMIT ? OFFSET ?");
        params.add(Math.clamp(limit, 1, 1000));
        params.add(Math.max(offset, 0));
        return database.select(sql.toString(), PgEvalCaseRow.class, params.toArray()).stream()
            .map(PgEvaluationStore::toCase).toList();
    }

    @Override
    public long countCases(String datasetRevisionId, String split) {
        var sql = new StringBuilder(256).append("SELECT count(*) AS total FROM ").append(CASE_TABLE).append(" WHERE dataset_revision_id = ?");
        var params = new ArrayList<>();
        params.add(datasetRevisionId);
        if (split != null && !split.isBlank()) {
            sql.append(" AND split = ?");
            params.add(split);
        }
        return database.selectOne(sql.toString(), PgEvalCountRow.class, params.toArray()).map(row -> row.total).orElse(0L);
    }

    @Override
    public List<EvalCaseEntity> listCasesByIds(String datasetRevisionId, List<String> caseIds) {
        if (caseIds.isEmpty()) return List.of();
        var placeholders = String.join(",", java.util.Collections.nCopies(caseIds.size(), "?"));
        var params = new ArrayList<>();
        params.add(datasetRevisionId);
        params.addAll(caseIds);
        return database.select(CASE_SELECT + " WHERE dataset_revision_id = ? AND case_id IN (" + placeholders + ")",
                PgEvalCaseRow.class, params.toArray()).stream().map(PgEvaluationStore::toCase).toList();
    }

    @Override
    public long countCasesBySplit(String datasetRevisionId, String split) {
        return countCases(datasetRevisionId, split);
    }

    @Override
    public int nextGraderSuiteRevision(String evaluationId) {
        return database.selectOne("SELECT COALESCE(MAX(revision), 0) + 1 AS next_revision FROM " + SUITE_TABLE
            + " WHERE evaluation_id = ?", PgEvalNextRow.class, evaluationId).map(row -> row.nextRevision).orElse(1);
    }

    @Override
    public void insertGraderSuite(GraderSuiteEntity suite) {
        database.execute("INSERT INTO " + SUITE_TABLE + " (id, evaluation_id, revision, name, fingerprint, body_json, "
                + "test_receipt_json, created_by, created_at, data) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb)",
            suite.id, suite.evaluationId, suite.revision, suite.name, suite.fingerprint, JsonSanitizer.json(suite.bodyJson),
            suite.testReceiptJson == null ? null : JsonSanitizer.json(suite.testReceiptJson), suite.createdBy, suite.createdAt,
            JsonSanitizer.json(JsonUtil.toJson(suite)));
    }

    @Override
    public Optional<GraderSuiteEntity> getGraderSuite(String evaluationId, int revision) {
        return database.selectOne(SUITE_SELECT + " WHERE evaluation_id = ? AND revision = ?",
            PgGraderSuiteRow.class, evaluationId, revision).map(PgEvaluationStore::toSuite);
    }

    @Override
    public Optional<GraderSuiteEntity> getGraderSuiteById(String id) {
        return database.selectOne(SUITE_SELECT + " WHERE id = ?", PgGraderSuiteRow.class, id)
            .map(PgEvaluationStore::toSuite);
    }

    @Override
    public List<GraderSuiteEntity> listGraderSuites(String evaluationId) {
        return database.select(SUITE_SELECT + " WHERE evaluation_id = ? ORDER BY revision DESC",
                PgGraderSuiteRow.class, evaluationId).stream().map(PgEvaluationStore::toSuite).toList();
    }

    @Override
    public int nextSpecRevision(String evaluationId) {
        return database.selectOne("SELECT COALESCE(MAX(revision), 0) + 1 AS next_revision FROM " + SPEC_TABLE
            + " WHERE evaluation_id = ?", PgEvalNextRow.class, evaluationId).map(row -> row.nextRevision).orElse(1);
    }

    @Override
    public void insertSpec(EvalSpecEntity spec) {
        database.execute("INSERT INTO " + SPEC_TABLE + " (id, evaluation_id, revision, dataset_revision_id, grader_suite_id, "
                + "fingerprint, body_json, created_by, created_at, data) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb)",
            spec.id, spec.evaluationId, spec.revision, spec.datasetRevisionId, spec.graderSuiteId, spec.fingerprint,
            JsonSanitizer.json(spec.bodyJson), spec.createdBy, spec.createdAt, JsonSanitizer.json(JsonUtil.toJson(spec)));
    }

    @Override
    public Optional<EvalSpecEntity> getSpec(String evaluationId, int revision) {
        return database.selectOne(SPEC_SELECT + " WHERE evaluation_id = ? AND revision = ?",
            PgSpecRow.class, evaluationId, revision).map(PgEvaluationStore::toSpec);
    }

    @Override
    public Optional<EvalSpecEntity> getSpecById(String id) {
        return database.selectOne(SPEC_SELECT + " WHERE id = ?", PgSpecRow.class, id).map(PgEvaluationStore::toSpec);
    }

    @Override
    public List<EvalSpecEntity> listSpecs(String evaluationId) {
        return database.select(SPEC_SELECT + " WHERE evaluation_id = ? ORDER BY revision DESC",
                PgSpecRow.class, evaluationId).stream().map(PgEvaluationStore::toSpec).toList();
    }

    @Override
    public Optional<EvalSpecEntity> findSpecByFingerprint(String evaluationId, String fingerprint) {
        return database.selectOne(SPEC_SELECT + " WHERE evaluation_id = ? AND fingerprint = ? ORDER BY revision DESC LIMIT 1",
            PgSpecRow.class, evaluationId, fingerprint).map(PgEvaluationStore::toSpec);
    }

    @Override
    public void insertSnapshot(CandidateSnapshotEntity snapshot) {
        database.execute("INSERT INTO " + SNAPSHOT_TABLE + " (id, evaluation_id, agent_id, source, source_revision, fingerprint, "
                + "snapshot_json, created_by, created_at, data) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb)",
            snapshot.id, snapshot.evaluationId, snapshot.agentId, snapshot.source, snapshot.sourceRevision,
            snapshot.fingerprint, JsonSanitizer.json(snapshot.snapshotJson), snapshot.createdBy, snapshot.createdAt,
            JsonSanitizer.json(JsonUtil.toJson(snapshot)));
    }

    @Override
    public Optional<CandidateSnapshotEntity> getSnapshot(String id) {
        return database.selectOne(SNAPSHOT_SELECT + " WHERE id = ?", PgSnapshotRow.class, id)
            .map(PgEvaluationStore::toSnapshot);
    }

    @Override
    public List<CandidateSnapshotEntity> listSnapshots(String evaluationId) {
        return database.select(SNAPSHOT_SELECT + " WHERE evaluation_id = ? ORDER BY created_at DESC",
                PgSnapshotRow.class, evaluationId).stream().map(PgEvaluationStore::toSnapshot).toList();
    }
}
