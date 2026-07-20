package ai.core.server.web;

import ai.core.api.server.workflow.ArtifactView;
import ai.core.api.server.workflow.CreateRunRequest;
import ai.core.api.server.workflow.NodeRunTraceMetadataView;
import ai.core.api.server.workflow.UnresolvedReferenceView;
import ai.core.api.server.workflow.WorkflowRunView;
import ai.core.api.server.workflow.WorkflowVersionView;
import ai.core.api.server.workflow.WorkflowView;
import ai.core.server.domain.ArtifactRef;
import ai.core.server.domain.TokenUsage;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowNodeTraceMetadata;
import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.domain.WorkflowVisibility;
import ai.core.server.workflow.WorkflowDefinitionService;
import ai.core.server.workflow.WorkflowPortService;
import ai.core.server.workflow.WorkflowRunService;
import core.framework.web.exception.BadRequestException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @author stephen
 */
class WorkflowViewMapper {

    static UnresolvedReferenceView toUnresolvedView(WorkflowPortService.UnresolvedReference ref) {
        var view = new UnresolvedReferenceView();
        view.nodeId = ref.nodeId();
        view.nodeType = ref.nodeType();
        view.refType = ref.refType();
        view.refId = ref.refId();
        view.message = ref.message();
        return view;
    }

    static WorkflowView toView(WorkflowDefinition definition) {
        return toView(definition, null);
    }

    static WorkflowView toView(WorkflowDefinition definition, String userName) {
        var view = new WorkflowView();
        view.id = definition.id;
        view.userId = definition.userId;
        view.userName = userName;
        view.name = definition.name;
        view.mode = definition.mode != null ? definition.mode.name() : null;
        var status = WorkflowDefinitionService.statusOf(definition);
        var visibility = WorkflowDefinitionService.visibilityOf(definition);
        view.visibility = visibility.name();
        view.status = status.name();
        if ("ACTIVE".equals(view.status)) {
            view.status = visibility == WorkflowVisibility.PUBLIC && definition.publishedVersionId != null ? "PUBLIC" : "PRIVATE";
        }
        view.publishedVersion = definition.publishedVersion;
        view.publishedVersionId = definition.publishedVersionId;
        view.draftGraph = definition.draftGraph;
        return view;
    }

    static WorkflowVersionView toVersionView(WorkflowPublishedVersion version, String currentPublicVersionId, boolean publicActive) {
        var view = new WorkflowVersionView();
        view.id = version.id;
        view.workflowId = version.workflowId;
        view.version = version.version;
        view.preview = version.preview;
        view.status = version.status != null ? version.status.name() : "ACTIVE";
        view.sha256 = version.sha256;
        view.publishedBy = version.publishedBy;
        view.publishedAt = version.publishedAt;
        view.currentPublic = publicActive && version.id.equals(currentPublicVersionId);
        return view;
    }

    static WorkflowRunView toRunView(WorkflowRun run) {
        var view = new WorkflowRunView();
        view.id = run.id;
        view.workflowId = run.workflowId;
        view.status = run.status != null ? run.status.name() : null;
        view.visibility = WorkflowRunService.visibilityOf(run.visibility).name();
        view.input = run.input;
        view.output = run.output;
        view.artifacts = toArtifactViews(run.artifacts);
        view.error = run.error;
        view.startedAt = run.startedAt;
        view.completedAt = run.completedAt;
        view.resumedFromRunId = run.resumedFromRunId;
        view.resumeFromNodeId = run.resumeFromNodeId;
        view.parentRunId = run.parentRunId;
        view.parentNodeId = run.parentNodeId;
        return view;
    }

    static WorkflowVisibility runVisibility(CreateRunRequest request) {
        // null = no explicit choice; the run service then inherits the workflow's visibility
        if (request == null || request.visibility == null || request.visibility.isBlank()) {
            return null;
        }
        var trimmed = request.visibility.trim().toUpperCase(Locale.getDefault());
        for (var v : WorkflowVisibility.values()) {
            if (v.name().equals(trimmed)) {
                return v;
            }
        }
        throw new BadRequestException("invalid run visibility: " + request.visibility);
    }

    static NodeRunTraceMetadataView toTraceMetadataView(WorkflowNodeTraceMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        var view = new NodeRunTraceMetadataView();
        view.agentId = metadata.agentId;
        view.agentName = metadata.agentName;
        view.model = metadata.model;
        view.multiModalModel = metadata.multiModalModel;
        view.childTraceId = metadata.childTraceId;
        view.childStatus = metadata.childStatus;
        view.tokenUsage = toTokenUsageMap(metadata.tokenUsage);
        return view;
    }

    static Map<String, Long> toTokenUsageMap(TokenUsage usage) {
        if (usage == null) {
            return null;
        }
        var map = new LinkedHashMap<String, Long>();
        if (usage.input != null) map.put("input", usage.input);
        if (usage.output != null) map.put("output", usage.output);
        return map.isEmpty() ? null : map;
    }

    static List<ArtifactView> toArtifactViews(List<ArtifactRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        return refs.stream().map(WorkflowViewMapper::toArtifactView).toList();
    }

    static ArtifactView toArtifactView(ArtifactRef ref) {
        var view = new ArtifactView();
        view.fileId = ref.fileId;
        view.fileName = ref.fileName;
        view.contentType = ref.contentType;
        view.size = ref.size;
        view.url = ref.url;
        view.title = ref.title;
        view.description = ref.description;
        return view;
    }
}
