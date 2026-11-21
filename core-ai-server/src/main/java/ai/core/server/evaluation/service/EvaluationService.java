package ai.core.server.evaluation.service;

import ai.core.server.agent.AgentDefinitionStore;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.evaluation.domain.DatasetRevisionEntity;
import ai.core.server.evaluation.domain.EvalCaseEntity;
import ai.core.server.evaluation.domain.EvalSpecEntity;
import ai.core.server.evaluation.domain.EvaluationEntity;
import ai.core.server.evaluation.domain.GraderSuiteEntity;
import ai.core.server.evaluation.store.EvaluationStore;
import ai.core.server.rbac.PermissionCodes;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Evaluation aggregate root: ACL, project metadata and immutable revision creation.
 *
 * @author stephen
 */
public class EvaluationService {
    @Inject
    EvaluationStore evaluationStore;
    @Inject
    AgentDefinitionStore agentDefinitionStore;
    @Inject
    PermissionService permissionService;
    @Inject
    EvalDatasetImporter datasetImporter;
    @Inject
    EvalGraderSuiteService graderSuiteService;
    @Inject
    EvalSpecService specService;

    public EvaluationEntity create(String agentId, String name, String description, String actorId) {
        requireHuman(actorId);
        permissionService.check(actorId, PermissionCodes.EVALUATION_MANAGE);
        if (agentId == null || agentId.isBlank()) throw new BadRequestException("agentId is required");
        if (name == null || name.isBlank()) throw new BadRequestException("name is required");
        agentDefinitionStore.get(agentId)
            .orElseThrow(() -> new BadRequestException("agent not found: " + agentId));
        permissionService.check(actorId, PermissionService.RESOURCE_TYPE_AGENT, agentId);
        var entity = new EvaluationEntity();
        entity.id = UUID.randomUUID().toString();
        entity.ownerId = actorId;
        entity.agentId = agentId;
        entity.name = name.trim();
        entity.description = description;
        entity.baselineGeneration = 0;
        entity.createdAt = ZonedDateTime.now();
        entity.updatedAt = entity.createdAt;
        evaluationStore.insertEvaluation(entity);
        return entity;
    }

    public List<EvaluationEntity> list(String actorId, String agentId, int offset, int limit) {
        requireHuman(actorId);
        permissionService.check(actorId, PermissionCodes.EVALUATION_VIEW);
        var all = permissionService.has(actorId, PermissionCodes.EVALUATION_MANAGE);
        return evaluationStore.listEvaluations(actorId, all, agentId, offset, limit);
    }

    public long count(String actorId, String agentId) {
        var all = permissionService.has(actorId, PermissionCodes.EVALUATION_MANAGE);
        return evaluationStore.countEvaluations(actorId, all, agentId);
    }

    public EvaluationEntity get(String id, String actorId) {
        requireHuman(actorId);
        permissionService.check(actorId, PermissionCodes.EVALUATION_VIEW);
        return requireVisible(id, actorId);
    }

    public EvaluationEntity requireVisible(String id, String actorId) {
        requireHuman(actorId);
        var entity = evaluationStore.getEvaluation(id)
            .orElseThrow(() -> new NotFoundException("evaluation not found: " + id));
        if (entity.archivedAt != null) throw new NotFoundException("evaluation is archived: " + id);
        if (!actorId.equals(entity.ownerId) && !permissionService.has(actorId, PermissionCodes.EVALUATION_MANAGE)) {
            throw new ForbiddenException("evaluation belongs to another user");
        }
        return entity;
    }

    public EvaluationEntity requireManage(String id, String actorId) {
        requireHuman(actorId);
        permissionService.check(actorId, PermissionCodes.EVALUATION_MANAGE);
        return requireVisible(id, actorId);
    }

    public EvaluationEntity requireRun(String id, String actorId) {
        var entity = requireVisible(id, actorId);
        permissionService.check(actorId, PermissionCodes.EVALUATION_RUN);
        return entity;
    }

    public EvaluationEntity update(String id, String name, String description, String actorId) {
        var entity = requireManage(id, actorId);
        if (name != null && !name.isBlank()) entity.name = name.trim();
        if (description != null) entity.description = description;
        entity.updatedAt = ZonedDateTime.now();
        if (!evaluationStore.replaceEvaluation(entity)) {
            throw new NotFoundException("evaluation not found: " + id);
        }
        return entity;
    }

    public void archive(String id, String actorId) {
        var entity = requireManage(id, actorId);
        if (!evaluationStore.archiveEvaluation(entity.id)) {
            throw new NotFoundException("evaluation not found: " + id);
        }
    }

    public List<DatasetRevisionEntity> listDatasets(String id, String actorId) {
        requireVisible(id, actorId);
        return evaluationStore.listDatasetRevisions(id);
    }

    public DatasetRevisionEntity requireDataset(String id, String datasetId, String actorId) {
        requireVisible(id, actorId);
        var dataset = evaluationStore.getDatasetRevisionById(datasetId)
            .orElseThrow(() -> new NotFoundException("dataset revision not found: " + datasetId));
        if (!id.equals(dataset.evaluationId)) throw new NotFoundException("dataset revision not found: " + datasetId);
        return dataset;
    }

    public List<EvalCaseEntity> listCases(String datasetId, String split, int offset, int limit) {
        return evaluationStore.listCases(datasetId, split, offset, limit);
    }

    public long countCases(String datasetId, String split) {
        return evaluationStore.countCases(datasetId, split);
    }

    public List<GraderSuiteEntity> listGraderSuites(String id, String actorId) {
        requireVisible(id, actorId);
        return evaluationStore.listGraderSuites(id);
    }

    public List<EvalSpecEntity> listSpecs(String id, String actorId) {
        requireVisible(id, actorId);
        return evaluationStore.listSpecs(id);
    }

    public DatasetRevisionEntity importDataset(String id, String fileId, String name, String actorId) {
        var evaluation = requireManage(id, actorId);
        if (fileId == null || fileId.isBlank()) throw new BadRequestException("fileId is required");
        return datasetImporter.importJsonl(evaluation, fileId, name, actorId);
    }

    public String testGraderSuite(String id, String bodyJson, String actorId) {
        requireManage(id, actorId);
        return graderSuiteService.test(bodyJson);
    }

    public GraderSuiteEntity createGraderSuite(String id, String bodyJson, String name, String actorId) {
        var evaluation = requireManage(id, actorId);
        return graderSuiteService.create(evaluation, bodyJson, name, actorId);
    }

    public EvalSpecEntity createSpec(String id, EvalSpecDraft draft, String actorId) {
        var evaluation = requireManage(id, actorId);
        return specService.create(evaluation, draft, actorId);
    }

    public void requireHuman(String actorId) {
        if (actorId == null || actorId.isBlank()) throw new ForbiddenException("user is required");
        if (permissionService.isApiUser(actorId)) {
            throw new ForbiddenException("evaluation is a human-only surface; API users cannot manage or run it");
        }
    }
}
