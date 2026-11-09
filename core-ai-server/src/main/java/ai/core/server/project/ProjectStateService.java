package ai.core.server.project;

import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectActionItem;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectEvent;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Subject-state write surface of the project feature. Current state (phase/summary/action items)
 * is written onto the subject document with targeted {@code $set}s; every change appends an
 * event row (D7), and KPIs/notes are events ONLY — the event collection is the authoritative
 * history consumed by the cockpit views, the timeline and the HTML report renderer.
 *
 * @author stephen
 */
public class ProjectStateService {
    static final int MAX_ACTION_ITEMS = 500;
    static final int KPI_VALUE_MAX_LENGTH = 200;
    static final int TEXT_MAX_LENGTH = 2000;
    static final int TITLE_MAX_LENGTH = 200;

    @Inject
    ProjectStore projectStore;
    @Inject
    ProjectSubjectStore subjectStore;
    @Inject
    ProjectEventStore eventStore;

    /**
     * Overwrites the subject's current phase/summary and appends phase/summary events on change. The
     * event time is the MATERIAL time (when the phase actually happened). Material OLDER than the
     * current state still lands in history but does not regress the current state (analysis consumes
     * material in batches whose order is not guaranteed).
     */
    public void updateStatus(String projectId, String subjectId, String phase, String summary, ZonedDateTime at, String updatedBy) {
        var subject = validateWrite(projectId, subjectId);
        var newPhase = limitText(phase, TEXT_MAX_LENGTH);
        var newSummary = limitText(summary, TEXT_MAX_LENGTH);
        if (newPhase == null && newSummary == null) return;
        var eventAt = eventAt(at);
        var phaseChanged = newPhase != null && !newPhase.equals(subject.phase);
        var summaryChanged = newSummary != null && !newSummary.equals(subject.summary);
        var older = subject.statusUpdatedAt != null && eventAt.isBefore(subject.statusUpdatedAt);
        if (!older) {
            subjectStore.update(subjectId, entity -> {
                if (newPhase != null) entity.phase = newPhase;
                if (newSummary != null) entity.summary = newSummary;
                entity.statusUpdatedAt = eventAt;
                entity.statusUpdatedBy = updatedBy;
                entity.updatedAt = ZonedDateTime.now();
            });
        }
        if (phaseChanged) {
            var meta = subject.phase == null ? null : JsonUtil.toJson(Map.of("previous_phase", subject.phase));
            recordEvent(projectId, subjectId, ProjectSubjectEvent.TYPE_PHASE, updatedBy, new EventValue(newPhase, newPhase, meta, eventAt));
        }
        if (summaryChanged) {
            var phaseKey = newPhase != null ? newPhase : subject.phase;
            recordEvent(projectId, subjectId, ProjectSubjectEvent.TYPE_SUMMARY, updatedBy, new EventValue(phaseKey, newSummary, null, eventAt));
        }
    }

    // KPIs are an append-only series: one event per observation, deduplicated on the exact
    // (key, value, at) so a re-analysis of the same material cannot double a data point
    public void recordKpi(String projectId, String subjectId, String createdBy, ZonedDateTime at, KpiSnapshot kpi) {
        validateWrite(projectId, subjectId);
        var key = limitText(kpi.key(), TITLE_MAX_LENGTH);
        var value = limitText(kpi.value(), KPI_VALUE_MAX_LENGTH);
        if (key == null || value == null) throw new BadRequestException("kpi key and value are required");
        var unit = limitText(kpi.unit(), TITLE_MAX_LENGTH);
        var meta = unit != null ? JsonUtil.toJson(Map.of("unit", unit)) : null;
        recordEventOnce(projectId, subjectId, ProjectSubjectEvent.TYPE_KPI, createdBy, new EventValue(key, value, meta, eventAt(at)));
    }

    public void updateActionItem(String projectId, String updatedBy, ActionItemFields fields) {
        var subjectId = fields.subjectId();
        var subject = validateWrite(projectId, subjectId);
        if (fields.title() == null || fields.title().isBlank()) throw new BadRequestException("title is required");
        if (fields.status() != null && !List.of("open", "in_progress", "done").contains(fields.status())) {
            throw new BadRequestException("invalid action item status: " + fields.status());
        }
        var items = new ArrayList<ProjectActionItem>();
        if (subject.actionItems != null) items.addAll(subject.actionItems);
        var eventAt = eventAt(fields.at());
        var title = limitText(fields.title(), TITLE_MAX_LENGTH);
        if (fields.itemId() == null || fields.itemId().isBlank()) {
            if (items.size() >= MAX_ACTION_ITEMS) {
                throw new BadRequestException("subject has too many action items, limit=" + MAX_ACTION_ITEMS);
            }
            var item = new ProjectActionItem();
            item.id = UUID.randomUUID().toString();
            item.subjectId = subjectId;
            item.title = title;
            item.status = fields.status() != null ? fields.status() : "open";
            item.note = limitText(fields.note(), TEXT_MAX_LENGTH);
            item.createdAt = eventAt;
            item.updatedAt = eventAt;
            item.updatedBy = updatedBy;
            items.add(item);
            recordActionItemEvent(projectId, subjectId, item, updatedBy, eventAt);
        } else {
            var item = items.stream().filter(i -> fields.itemId().equals(i.id)).findFirst()
                .orElseThrow(() -> new BadRequestException("action item not found, id=" + fields.itemId()));
            var statusChanged = fields.status() != null && !fields.status().equals(item.status);
            var titleChanged = !title.equals(item.title);
            item.title = title;
            if (fields.status() != null) item.status = fields.status();
            if (fields.note() != null) item.note = limitText(fields.note(), TEXT_MAX_LENGTH);
            item.updatedAt = eventAt;
            item.updatedBy = updatedBy;
            if (statusChanged || titleChanged) recordActionItemEvent(projectId, subjectId, item, updatedBy, eventAt);
        }
        subjectStore.update(subjectId, entity -> {
            entity.actionItems = items;
            entity.updatedAt = ZonedDateTime.now();
        });
    }

    public void addNote(String projectId, String subjectId, String content, ZonedDateTime at, String createdBy) {
        validateWrite(projectId, subjectId);
        if (content == null || content.isBlank()) throw new BadRequestException("content is required");
        recordEventOnce(projectId, subjectId, ProjectSubjectEvent.TYPE_NOTE, createdBy,
            new EventValue(null, limitText(content, TEXT_MAX_LENGTH), null, eventAt(at)));
    }

    // subject tracking status (started/paused): the subject.status field itself, with a history
    // event so the timeline shows when tracking started/paused
    public void recordSubjectStatus(String projectId, String subjectId, String status, String updatedBy) {
        var subject = requireSubject(projectId, subjectId);
        if (status == null || status.equals(subject.status)) return;
        subjectStore.update(subjectId, entity -> {
            entity.status = status;
            entity.updatedAt = ZonedDateTime.now();
        });
        recordEvent(projectId, subjectId, ProjectSubjectEvent.TYPE_SUBJECT_STATUS, updatedBy, new EventValue(null, status, null, ZonedDateTime.now()));
    }

    public void deleteSubjectEvents(String subjectId) {
        eventStore.deleteBySubject(subjectId);
    }

    private void recordActionItemEvent(String projectId, String subjectId, ProjectActionItem item, String updatedBy, ZonedDateTime at) {
        recordEvent(projectId, subjectId, ProjectSubjectEvent.TYPE_ACTION_ITEM, updatedBy,
            new EventValue(item.id, item.status, JsonUtil.toJson(Map.of("title", item.title)), at));
    }

    // idempotent append: an identical (type, key, value, at) row for the subject already exists →
    // skip (crash-replay / re-analysis safety); the (subject_id, at) index serves the lookup
    private void recordEventOnce(String projectId, String subjectId, String type, String createdBy, EventValue fields) {
        if (eventStore.exists(projectId, subjectId, type, fields.key(), fields.value(), fields.at())) return;
        recordEvent(projectId, subjectId, type, createdBy, fields);
    }

    private void recordEvent(String projectId, String subjectId, String type, String createdBy, EventValue fields) {
        var event = new ProjectSubjectEvent();
        event.id = UUID.randomUUID().toString();
        event.projectId = projectId;
        event.subjectId = subjectId;
        event.type = type;
        event.key = fields.key();
        event.value = fields.value();
        event.meta = fields.meta();
        event.at = fields.at();
        event.createdBy = createdBy;
        eventStore.insert(event);
    }

    // the event time is the MATERIAL time (when the fact actually happened): the analyzer passes
    // the date it found in the material. Missing, future or implausibly old dates fall back to now.
    private ZonedDateTime eventAt(ZonedDateTime at) {
        var now = ZonedDateTime.now();
        if (at == null) return now;
        if (at.isAfter(now.plusDays(1)) || at.isBefore(now.minusYears(5))) return now;
        return at;
    }

    // every state write requires a subject: the project itself holds no state (it is a scaffold)
    private ProjectSubject validateWrite(String projectId, String subjectId) {
        require(projectId);
        if (subjectId == null || subjectId.isBlank()) throw new BadRequestException("subject_id is required: the project itself holds no state, state belongs to subjects");
        return requireSubject(projectId, subjectId);
    }

    private Project require(String projectId) {
        return projectStore.get(projectId)
            .orElseThrow(() -> new core.framework.web.exception.NotFoundException("project not found, id=" + projectId));
    }

    private ProjectSubject requireSubject(String projectId, String subjectId) {
        var subject = subjectStore.get(subjectId).orElse(null);
        if (subject == null || !projectId.equals(subject.projectId)) {
            throw new BadRequestException("subject does not belong to the project, subjectId=" + subjectId);
        }
        return subject;
    }

    private String limitText(String value, int maxLength) {
        if (value == null) return null;
        var trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    public record ActionItemFields(String subjectId, String itemId, String title, String status, String note, ZonedDateTime at) {
    }

    public record KpiSnapshot(String key, String value, String unit) {
    }

    private record EventValue(String key, String value, String meta, ZonedDateTime at) {
    }
}
