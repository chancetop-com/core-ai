package ai.core.server.project;

import ai.core.api.server.project.ProjectActionItemView;
import ai.core.api.server.project.ProjectAgentStatView;
import ai.core.api.server.project.ProjectEventView;
import ai.core.api.server.project.ProjectExecutionView;
import ai.core.api.server.project.ProjectKpiView;
import ai.core.api.server.project.ProjectNoteView;
import ai.core.api.server.project.ProjectReportSourceView;
import ai.core.api.server.project.ProjectReportView;
import ai.core.api.server.project.ProjectSubjectStatView;
import ai.core.api.server.project.ProjectSubjectStatusView;
import ai.core.api.server.project.ProjectSubjectView;
import ai.core.api.server.project.TimelineEntryView;
import ai.core.server.domain.ProjectActionItem;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectEvent;
import core.framework.inject.Inject;

/**
 * View converters of the project web service, extracted to keep the service class within the
 * file-length budget. Pure mapping — no business logic. Current state comes from the subject
 * document, KPI/note series from the event collection.
 *
 * @author stephen
 */
public class ProjectViewAssembler {
    @Inject
    ProjectQueryService queryService;

    ProjectReportSourceView toReportSourceView(ai.core.server.domain.ProjectReportSource source) {
        var view = new ProjectReportSourceView();
        view.type = source.type;
        view.id = source.id;
        view.name = source.name;
        return view;
    }

    ProjectSubjectView toSubjectView(ProjectSubject subject) {
        var view = new ProjectSubjectView();
        view.id = subject.id;
        view.name = subject.name;
        view.description = subject.description;
        view.externalLink = subject.externalLink;
        view.status = subject.status;
        view.attributedCount = queryService.attributionCount(subject.id);
        view.profile = subject.profile;
        view.analyzedAt = subject.analyzedAt;
        view.reportFileId = subject.reportFileId;
        view.reportShareToken = subject.reportShareToken;
        view.reportGeneratedAt = subject.reportGeneratedAt;
        view.reportError = subject.reportError;
        view.reportRunId = subject.reportRunId;
        view.createdAt = subject.createdAt;
        view.updatedAt = subject.updatedAt;
        return view;
    }

    // null when the subject has no phase/summary yet (the cockpit lists only subjects with a status)
    ProjectSubjectStatusView toSubjectStatusView(ProjectSubject subject) {
        if (subject.phase == null && subject.summary == null) return null;
        var view = new ProjectSubjectStatusView();
        view.subjectId = subject.id;
        view.phase = subject.phase;
        view.summary = subject.summary;
        view.updatedAt = subject.statusUpdatedAt;
        view.updatedBy = subject.statusUpdatedBy;
        return view;
    }

    ProjectKpiView toKpiView(ProjectSubjectEvent event) {
        var view = new ProjectKpiView();
        view.subjectId = event.subjectId;
        view.key = event.key;
        view.value = event.value;
        view.unit = ProjectQueryService.metaValue(event.meta, "unit", null);
        view.createdAt = event.at;
        view.createdBy = event.createdBy;
        return view;
    }

    ProjectActionItemView toActionItemView(ProjectSubject subject, ProjectActionItem item) {
        var view = new ProjectActionItemView();
        view.subjectId = subject.id;
        view.id = item.id;
        view.title = item.title;
        view.status = item.status;
        view.note = item.note;
        view.createdAt = item.createdAt;
        view.updatedAt = item.updatedAt;
        view.updatedBy = item.updatedBy;
        return view;
    }

    ProjectNoteView toNoteView(ProjectSubjectEvent event) {
        var view = new ProjectNoteView();
        view.subjectId = event.subjectId;
        view.content = event.value;
        view.createdAt = event.at;
        view.createdBy = event.createdBy;
        return view;
    }

    ProjectExecutionView toExecutionView(ProjectQueryService.ProjectExecution row) {
        var view = new ProjectExecutionView();
        view.id = row.id();
        view.type = row.type();
        view.title = row.title();
        view.agentName = row.agentName();
        view.status = row.status();
        view.startedAt = row.startedAt();
        view.inputTokens = row.inputTokens();
        view.outputTokens = row.outputTokens();
        view.costUsd = row.costUsd();
        view.traceId = row.traceId();
        view.subjectId = row.subjectId();
        return view;
    }

    ProjectReportView toReportView(ProjectReportQueryService.ProjectReport row) {
        var view = new ProjectReportView();
        view.fileId = row.fileId();
        view.fileName = row.fileName();
        view.contentType = row.contentType();
        view.size = row.size();
        view.createdAt = row.createdAt();
        view.subjectId = row.subjectId();
        view.agentId = row.agentId();
        view.agentName = row.agentName();
        view.source = row.source();
        view.shareToken = row.shareToken();
        return view;
    }

    ProjectAgentStatView toAgentStatView(ProjectQueryService.StatRow row) {
        var view = new ProjectAgentStatView();
        view.agentId = row.groupId();
        view.agentName = row.name();
        view.tokens = row.tokens();
        view.costUsd = row.costUsd();
        view.count = row.count();
        return view;
    }

    ProjectSubjectStatView toSubjectStatView(ProjectQueryService.StatRow row) {
        var view = new ProjectSubjectStatView();
        view.subjectId = row.groupId();
        view.tokens = row.tokens();
        view.costUsd = row.costUsd();
        view.count = row.count();
        return view;
    }

    TimelineEntryView toTimelineView(ProjectQueryService.TimelineEntry entry) {
        var view = new TimelineEntryView();
        view.type = entry.type();
        view.title = entry.title();
        view.detail = entry.detail();
        view.subjectId = entry.subjectId();
        view.sessionId = entry.sessionId();
        view.traceId = entry.traceId();
        view.at = entry.at();
        return view;
    }

    ProjectEventView toEventView(ProjectSubjectEvent event) {
        var view = new ProjectEventView();
        view.id = event.id;
        view.subjectId = event.subjectId;
        view.type = event.type;
        view.key = event.key;
        view.value = event.value;
        view.meta = event.meta;
        view.at = event.at;
        view.createdBy = event.createdBy;
        return view;
    }
}
