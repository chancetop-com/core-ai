package ai.core.server.run;

import ai.core.api.server.run.AgentCallRequest;
import ai.core.api.server.run.AgentCallResponse;
import ai.core.api.server.run.AgentRunDetailView;
import ai.core.api.server.run.AgentRunView;
import ai.core.api.server.run.LLMCallRequest;
import ai.core.api.server.run.LLMCallResponse;
import ai.core.api.server.run.ListRunsRequest;
import ai.core.api.server.run.ListRunsResponse;
import ai.core.api.server.run.TriggerRunRequest;
import ai.core.api.server.run.TriggerRunResponse;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.agent.AgentDependencyAccessPolicy;
import ai.core.server.apiuser.ApiUserQuotaService;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TriggerType;
import ai.core.server.file.FileService;
import ai.core.server.skill.SkillService;
import ai.core.server.util.IdLists;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.Map;

/**
 * @author stephen
 */
public class AgentRunService {
    @Inject
    AgentRunner agentRunner;
    @Inject
    LLMCallExecutor llmCallExecutor;
    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;
    @Inject
    MongoCollection<AgentRun> agentRunCollection;
    @Inject
    FileService fileService;
    @Inject
    PublicUrlConfiguration publicUrlConfiguration;
    @Inject
    SkillService skillService;
    @Inject
    PermissionService permissionService;
    @Inject
    ApiUserQuotaService apiUserQuotaService;

    public TriggerRunResponse trigger(String agentId, TriggerRunRequest request, String callerUserId) {
        permissionService.check(callerUserId, PermissionService.RESOURCE_TYPE_AGENT, agentId);
        apiUserQuotaService.checkQuota(callerUserId);
        var source = agentDefinitionCollection.get(agentId).orElse(null);
        var definition = AgentDependencyAccessPolicy.executableTopLevelAgent(
            source, callerUserId);
        requireAccessibleEditableSkills(source, definition, callerUserId);

        var input = request.input != null ? request.input : resolveInputTemplate(definition);
        var runId = agentRunner.runAs(definition, input, TriggerType.MANUAL, callerUserId);

        var response = new TriggerRunResponse();
        response.runId = runId;
        response.status = ai.core.api.server.run.RunStatus.RUNNING;
        return response;
    }

    public ListRunsResponse listByAgent(String agentId, ListRunsRequest request) {
        var query = new Query();
        if (request.status != null && !request.status.isBlank()) {
            query.filter = Filters.and(
                Filters.eq("agent_id", agentId),
                Filters.eq("status", RunStatus.valueOf(request.status))
            );
        } else {
            query.filter = Filters.eq("agent_id", agentId);
        }
        query.sort = Sorts.descending("started_at");
        query.limit = request.limit != null ? request.limit : 20;

        var runs = agentRunCollection.find(query);
        var response = new ListRunsResponse();
        response.runs = runs.stream().map(this::toView).toList();
        response.total = agentRunCollection.count(query.filter);
        return response;
    }

    public AgentRunDetailView get(String id) {
        var entity = agentRunCollection.get(id)
            .orElseThrow(() -> new RuntimeException("run not found, id=" + id));
        return toDetailView(entity);
    }

    public LLMCallResponse llmCall(String id, LLMCallRequest request, String callerUserId) {
        permissionService.check(callerUserId, PermissionService.RESOURCE_TYPE_AGENT, id);
        apiUserQuotaService.checkQuota(callerUserId);
        var definition = AgentDependencyAccessPolicy.executablePublishedLlmCall(
            agentDefinitionCollection.get(id).orElse(null), callerUserId);

        var result = llmCallExecutor.execute(definition, request.input, request.attachments);

        var response = new LLMCallResponse();
        response.output = result.output();
        response.tokenUsage = Map.of(
            "input", result.inputTokens(),
            "output", result.outputTokens()
        );
        return response;
    }

    public AgentCallResponse call(String agentId, AgentCallRequest request, String callerUserId) {
        permissionService.check(callerUserId, PermissionService.RESOURCE_TYPE_AGENT, agentId);
        apiUserQuotaService.checkQuota(callerUserId);
        var source = agentDefinitionCollection.get(agentId).orElse(null);
        var definition = AgentDependencyAccessPolicy.executableTopLevelCallable(
            source, callerUserId);
        requireAccessibleEditableSkills(source, definition, callerUserId);

        var input = request.input;

        if (definition.type == DefinitionType.LLM_CALL) {
            var result = llmCallExecutor.execute(definition, input);
            var response = new AgentCallResponse();
            response.output = result.output();
            response.tokenUsage = Map.of("input", result.inputTokens(), "output", result.outputTokens());
            return response;
        }

        // Sync agent execution: create run record, execute, return result
        var runId = agentRunner.runAs(definition, input, TriggerType.MANUAL, callerUserId);
        // Wait for completion
        var maxWait = 600;
        for (int i = 0; i < maxWait; i++) {
            var run = agentRunCollection.get(runId).orElse(null);
            if (run != null && run.status != RunStatus.RUNNING && run.status != RunStatus.PENDING) {
                var response = new AgentCallResponse();
                response.runId = runId;
                response.output = run.output != null ? run.output : run.error;
                if (run.tokenUsage != null) {
                    response.tokenUsage = Map.of(
                        "input", run.tokenUsage.input != null ? run.tokenUsage.input : 0L,
                        "output", run.tokenUsage.output != null ? run.tokenUsage.output : 0L
                    );
                }
                return response;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new RuntimeException("agent call timed out after " + maxWait + "s, runId=" + runId);
    }

    private void requireAccessibleEditableSkills(AgentDefinition source, AgentDefinition executable,
                                                   String callerUserId) {
        if (executable.type != DefinitionType.AGENT
                || !AgentDependencyAccessPolicy.isOwnedEditable(source, callerUserId)
                || executable.publishedConfig != null) return;
        var skillIds = IdLists.clean(executable.skillIds);
        if (!skillIds.isEmpty()) skillService.resolveAccessibleSkills(skillIds, callerUserId);
    }

    private String resolveInputTemplate(AgentDefinition definition) {
        return definition.publishedConfig != null
            ? definition.publishedConfig.inputTemplate
            : definition.inputTemplate;
    }

    public void cancel(String id) {
        agentRunner.cancel(id);
    }

    private AgentRunView toView(AgentRun entity) {
        var view = new AgentRunView();
        view.id = entity.id;
        view.agentId = entity.agentId;
        view.triggeredBy = entity.triggeredBy.name();
        view.status = entity.status.name();
        view.input = entity.input;
        view.output = entity.output;
        view.error = entity.error;
        view.errorStack = entity.errorStack;
        view.traceId = entity.traceId;
        view.startedAt = entity.startedAt;
        view.completedAt = entity.completedAt;
        if (entity.tokenUsage != null) {
            view.tokenUsage = Map.of(
                "input", entity.tokenUsage.input != null ? entity.tokenUsage.input : 0L,
                "output", entity.tokenUsage.output != null ? entity.tokenUsage.output : 0L
            );
        }
        return view;
    }

    private AgentRunDetailView toDetailView(AgentRun entity) {
        var view = new AgentRunDetailView();
        view.id = entity.id;
        view.agentId = entity.agentId;
        view.triggeredBy = entity.triggeredBy.name();
        view.status = ai.core.api.server.run.RunStatus.valueOf(entity.status.name());
        view.input = entity.input;
        view.output = entity.output;
        view.error = entity.error;
        view.errorStack = entity.errorStack;
        view.traceId = entity.traceId;
        view.startedAt = entity.startedAt;
        view.completedAt = entity.completedAt;
        if (entity.tokenUsage != null) {
            view.tokenUsage = Map.of(
                "input", entity.tokenUsage.input != null ? entity.tokenUsage.input : 0L,
                "output", entity.tokenUsage.output != null ? entity.tokenUsage.output : 0L
            );
        }
        if (entity.transcript != null) {
            view.transcript = entity.transcript.stream().map(t -> {
                var entry = new AgentRunDetailView.TranscriptEntryView();
                entry.timestamp = t.timestamp != null ? t.timestamp.toString() : null;
                entry.role = t.role;
                entry.content = t.content;
                entry.name = t.name;
                entry.args = t.args;
                entry.status = t.status;
                entry.result = t.result;
                return entry;
            }).toList();
        }
        if (entity.artifacts != null) {
            view.artifacts = entity.artifacts.stream().map(a -> {
                var artifact = new AgentRunDetailView.ArtifactView();
                artifact.fileId = a.fileId;
                artifact.fileName = a.fileName;
                artifact.contentType = a.contentType;
                artifact.size = a.size;
                artifact.sourcePath = a.sourcePath;
                artifact.title = a.title;
                artifact.description = a.description;
                var shared = fileService.share(a.fileId, entity.userId);
                artifact.downloadUrl = publicUrlConfiguration.sharedArtifactDownloadUrl(shared.shareToken);
                artifact.createdAt = a.createdAt;
                return artifact;
            }).toList();
        }
        return view;
    }
}
