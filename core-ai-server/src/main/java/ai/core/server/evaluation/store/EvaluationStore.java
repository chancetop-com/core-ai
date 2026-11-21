package ai.core.server.evaluation.store;

import ai.core.server.evaluation.domain.CandidateSnapshotEntity;
import ai.core.server.evaluation.domain.DatasetRevisionEntity;
import ai.core.server.evaluation.domain.EvalCaseEntity;
import ai.core.server.evaluation.domain.EvalSpecEntity;
import ai.core.server.evaluation.domain.EvaluationEntity;
import ai.core.server.evaluation.domain.GraderSuiteEntity;

import java.util.List;
import java.util.Optional;

/**
 * Immutable evaluation definitions and their aggregate root.
 *
 * @author stephen
 */
public interface EvaluationStore {
    Optional<EvaluationEntity> getEvaluation(String id);

    void insertEvaluation(EvaluationEntity evaluation);

    boolean replaceEvaluation(EvaluationEntity evaluation);

    List<EvaluationEntity> listEvaluations(String ownerId, boolean all, String agentId, int offset, int limit);

    long countEvaluations(String ownerId, boolean all, String agentId);

    boolean archiveEvaluation(String id);

    int nextDatasetRevision(String evaluationId);

    void insertDatasetRevision(DatasetRevisionEntity revision);

    Optional<DatasetRevisionEntity> getDatasetRevision(String evaluationId, int revision);

    Optional<DatasetRevisionEntity> getDatasetRevisionById(String id);

    List<DatasetRevisionEntity> listDatasetRevisions(String evaluationId);

    void insertCases(List<EvalCaseEntity> cases);

    List<EvalCaseEntity> listCases(String datasetRevisionId, String split, int offset, int limit);

    long countCases(String datasetRevisionId, String split);

    List<EvalCaseEntity> listCasesByIds(String datasetRevisionId, List<String> caseIds);

    long countCasesBySplit(String datasetRevisionId, String split);

    int nextGraderSuiteRevision(String evaluationId);

    void insertGraderSuite(GraderSuiteEntity suite);

    Optional<GraderSuiteEntity> getGraderSuite(String evaluationId, int revision);

    Optional<GraderSuiteEntity> getGraderSuiteById(String id);

    List<GraderSuiteEntity> listGraderSuites(String evaluationId);

    int nextSpecRevision(String evaluationId);

    void insertSpec(EvalSpecEntity spec);

    Optional<EvalSpecEntity> getSpec(String evaluationId, int revision);

    Optional<EvalSpecEntity> getSpecById(String id);

    List<EvalSpecEntity> listSpecs(String evaluationId);

    Optional<EvalSpecEntity> findSpecByFingerprint(String evaluationId, String fingerprint);

    void insertSnapshot(CandidateSnapshotEntity snapshot);

    Optional<CandidateSnapshotEntity> getSnapshot(String id);

    List<CandidateSnapshotEntity> listSnapshots(String evaluationId);
}
