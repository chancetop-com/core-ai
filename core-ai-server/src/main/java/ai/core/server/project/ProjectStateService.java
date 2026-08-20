package ai.core.server.project;

import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectActionItem;
import ai.core.server.domain.ProjectKpiRecord;
import ai.core.server.domain.ProjectNote;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectEvent;
import ai.core.server.domain.ProjectSubjectStatus;
import ai.core.utils.JsonUtil;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Subject-state write surface of the project feature: status/KPIs/action items/notes plus the
 * append-only event rows (D7) behind every change. The embedded arrays on the project document
 * are the current-state surface for the existing UI; the event collection is the authoritative
 * history consumed by the timeline, trends and the HTML report renderer.
 *
 * @author stephen
 */
public class ProjectStateService {
    static final int MAX_KPIS = 2000;
    static final int MAX_ACTION_ITEMS = 500;
    static final int MAX_NOTES = 2000;
    static final int MAX_SUBJECT_STATUSES = 200;
    static final int KPI_VALUE_MAX_LENGTH = 200;
    static final int TEXT_MAX_LENGTH = 2000;
    static final int TITLE_MAX_LENGTH = 200;

    @Inject
    MongoCollection<Project> projectCollection;
    @Inject
    MongoCollection<ProjectSubject> subjectCollection;
    @Inject
    MongoCollection<ProjectSubjectEvent> eventCollection;

    public void updateStatus(String projectId, String subjectId, String phase, String summary, ZonedDateTime at, String updatedBy) {
        validateWrite(projectId, subjectId);
        var now = ZonedDateTime.now();
        var project = require(projectId);
        var statuses = new ArrayList<ProjectSubjectStatus>();
        if (project.subjectStatuses != null) statuses.addAll(project.subjectStatuses);
        var existing = statuses.stream().filter(s -> subjectId.equals(s.subjectId)).findFirst();
        ProjectSubjectStatus entry = existing.orElseGet(() -> {
            var created = new ProjectSubjectStatus();
            created.subjectId = subjectId;
            statuses.add(created);
            return created;
        });
        if (statuses.size() > MAX_SUBJECT_STATUSES) {
            throw new BadRequestException("project has too many subject statuses, limit=" + MAX_SUBJECT_STATUSES);
        }
        var newPhase = limitText(phase, TEXT_MAX_LENGTH);
        var newSummary = limitText(summary, TEXT_MAX_LENGTH);
        var oldPhase = entry.phase;
        var oldSummary = entry.summary;
        var eventAt = eventAt(at);
        if (newPhase != null) entry.phase = newPhase;
        if (newSummary != null) entry.summary = newSummary;
        entry.updatedAt = eventAt;
        entry.updatedBy = updatedBy;
        project.subjectStatuses = statuses;
        normalizeArrayFields(project);
        project.updatedAt = now;
        projectCollection.replace(project);
        // phase/summary changes append history events (D7): the transition itself is the insight;
        // the event time is the MATERIAL time (when the phase actually happened), not the write time
        if (newPhase != null && !newPhase.equals(oldPhase)) {
            var meta = oldPhase == null ? null : JsonUtil.toJson(Map.of("previous_phase", oldPhase));
            recordEvent(projectId, subjectId, ProjectSubjectEvent.TYPE_PHASE, updatedBy, new EventValue(newPhase, newPhase, meta, eventAt));
        }
        if (newSummary != null && !newSummary.equals(oldSummary)) {
            recordEvent(projectId, subjectId, ProjectSubjectEvent.TYPE_SUMMARY, updatedBy, new EventValue(entry.phase, newSummary, null, eventAt));
        }
    }

    public void recordKpi(String projectId, String subjectId, String createdBy, ZonedDateTime at, KpiSnapshot kpi) {
        validateWrite(projectId, subjectId);
        var project = require(projectId);
        var size = project.kpis != null ? project.kpis.size() : 0;
        var record = new ProjectKpiRecord();
        record.subjectId = subjectId;
        record.key = limitText(kpi.key(), TITLE_MAX_LENGTH);
        record.value = limitText(kpi.value(), KPI_VALUE_MAX_LENGTH);
        record.unit = limitText(kpi.unit(), TITLE_MAX_LENGTH);
        record.createdAt = eventAt(at);
        record.createdBy = createdBy;
        // the event series is the authoritative history; the embedded array is the current-state
        // surface. At the embedded cap the event still lands (analysis must not fail on volume).
        recordEvent(projectId, subjectId, ProjectSubjectEvent.TYPE_KPI, createdBy,
            new EventValue(record.key, record.value, record.unit != null ? JsonUtil.toJson(Map.of("unit", record.unit)) : null, record.createdAt));
        if (size >= MAX_KPIS) return;
        // push as a raw Document: core-ng has no codec for nested classes, so embedding an entity
        // instance as an update value fails with "Can't find a codec" (replace() works because the
        // entity encoder handles nested fields inline)
        var doc = new org.bson.Document("subject_id", record.subjectId)
            .append("key", record.key)
            .append("value", record.value);
        if (record.unit != null) doc.append("unit", record.unit);
        doc.append("created_at", record.createdAt).append("created_by", record.createdBy);
        ensureArrayField(projectId, "kpis", project.kpis == null);
        projectCollection.update(Filters.eq("_id", projectId), Updates.combine(
            Updates.push("kpis", doc),
            Updates.set("updated_at", ZonedDateTime.now())));
    }

    public void updateActionItem(String projectId, String updatedBy, ActionItemFields fields) {
        var subjectId = fields.subjectId();
        validateWrite(projectId, subjectId);
        if (fields.title() == null || fields.title().isBlank()) throw new BadRequestException("title is required");
        if (fields.status() != null && !List.of("open", "in_progress", "done").contains(fields.status())) {
            throw new BadRequestException("invalid action item status: " + fields.status());
        }
        var project = require(projectId);
        var items = new ArrayList<ProjectActionItem>();
        if (project.actionItems != null) items.addAll(project.actionItems);
        var now = ZonedDateTime.now();
        var eventAt = eventAt(fields.at());
        if (fields.itemId() == null || fields.itemId().isBlank()) {
            if (items.size() >= MAX_ACTION_ITEMS) {
                throw new BadRequestException("project has too many action items, limit=" + MAX_ACTION_ITEMS);
            }
            var item = new ProjectActionItem();
            item.id = UUID.randomUUID().toString();
            item.subjectId = subjectId;
            item.title = limitText(fields.title(), TITLE_MAX_LENGTH);
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
            var titleChanged = fields.title() != null && !limitText(fields.title(), TITLE_MAX_LENGTH).equals(item.title);
            item.subjectId = subjectId;
            item.title = limitText(fields.title(), TITLE_MAX_LENGTH);
            if (fields.status() != null) item.status = fields.status();
            if (fields.note() != null) item.note = limitText(fields.note(), TEXT_MAX_LENGTH);
            item.updatedAt = eventAt;
            item.updatedBy = updatedBy;
            if (statusChanged || titleChanged) recordActionItemEvent(projectId, subjectId, item, updatedBy, eventAt);
        }
        project.actionItems = items;
        normalizeArrayFields(project);
        project.updatedAt = now;
        projectCollection.replace(project);
    }

    public void addNote(String projectId, String subjectId, String content, ZonedDateTime at, String createdBy) {
        validateWrite(projectId, subjectId);
        if (content == null || content.isBlank()) throw new BadRequestException("content is required");
        var project = require(projectId);
        var size = project.notes != null ? project.notes.size() : 0;
        var note = new ProjectNote();
        note.subjectId = subjectId;
        note.content = limitText(content, TEXT_MAX_LENGTH);
        note.createdAt = eventAt(at);
        note.createdBy = createdBy;
        recordEvent(projectId, subjectId, ProjectSubjectEvent.TYPE_NOTE, createdBy, new EventValue(null, note.content, null, note.createdAt));
        if (size >= MAX_NOTES) return;
        // see recordKpi: raw Document because core-ng has no codec for nested classes
        var doc = new org.bson.Document("subject_id", note.subjectId)
            .append("content", note.content)
            .append("created_at", note.createdAt)
            .append("created_by", note.createdBy);
        ensureArrayField(projectId, "notes", project.notes == null);
        projectCollection.update(Filters.eq("_id", projectId), Updates.combine(
            Updates.push("notes", doc),
            Updates.set("updated_at", ZonedDateTime.now())));
    }

    // subject tracking status (started/paused): the subject.status field itself, with a history
    // event so the timeline shows when tracking started/paused
    public void recordSubjectStatus(String projectId, String subjectId, String status, String updatedBy) {
        var subject = requireSubject(projectId, subjectId);
        if (status == null || status.equals(subject.status)) return;
        subjectCollection.update(Filters.eq("_id", subjectId), Updates.combine(
            Updates.set("status", status),
            Updates.set("updated_at", ZonedDateTime.now())));
        recordEvent(projectId, subjectId, ProjectSubjectEvent.TYPE_SUBJECT_STATUS, updatedBy, new EventValue(null, status, null, ZonedDateTime.now()));
    }

    public void deleteSubjectEvents(String subjectId) {
        eventCollection.delete(Filters.eq("subject_id", subjectId));
    }

    private void recordActionItemEvent(String projectId, String subjectId, ProjectActionItem item, String updatedBy, ZonedDateTime at) {
        recordEvent(projectId, subjectId, ProjectSubjectEvent.TYPE_ACTION_ITEM, updatedBy,
            new EventValue(item.id, item.status, JsonUtil.toJson(Map.of("title", item.title)), at));
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
        eventCollection.insert(event);
    }

    // the event time is the MATERIAL time (when the fact actually happened): the analyzer passes
    // the date it found in the material. Missing, future or implausibly old dates fall back to now.
    private ZonedDateTime eventAt(ZonedDateTime at) {
        var now = ZonedDateTime.now();
        if (at == null) return now;
        if (at.isAfter(now.plusDays(1)) || at.isBefore(now.minusYears(5))) return now;
        return at;
    }

    // core-ng's entity encoder persists null fields verbatim on replace(), so a null array field
    // can end up in the document and break $push later — normalize them back to empty arrays
    private void normalizeArrayFields(Project project) {
        if (project.kpis == null) project.kpis = List.of();
        if (project.notes == null) project.notes = List.of();
    }

    // $push requires the field to be absent or an array; a persisted null must be fixed first
    private void ensureArrayField(String projectId, String field, boolean missing) {
        if (missing) {
            projectCollection.update(Filters.eq("_id", projectId), Updates.set(field, List.of()));
        }
    }

    // every state write requires a subject: the project itself holds no state (it is a scaffold)
    private void validateWrite(String projectId, String subjectId) {
        require(projectId);
        if (subjectId == null || subjectId.isBlank()) throw new BadRequestException("subject_id is required: the project itself holds no state, state belongs to subjects");
        requireSubject(projectId, subjectId);
    }

    private Project require(String projectId) {
        return projectCollection.get(projectId)
            .orElseThrow(() -> new core.framework.web.exception.NotFoundException("project not found, id=" + projectId));
    }

    private ProjectSubject requireSubject(String projectId, String subjectId) {
        var subject = subjectCollection.get(subjectId).orElse(null);
        if (subject == null || !projectId.equals(subject.projectId)) {
            throw new BadRequestException("subject does not belong to the project, subjectId=" + subjectId);
        }
        return subject;
    }

    private String limitText(String value, int maxLength) {
        if (value == null) return null;
        var trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    public record ActionItemFields(String subjectId, String itemId, String title, String status, String note, ZonedDateTime at) {
    }

    public record KpiSnapshot(String key, String value, String unit) {
    }

    private record EventValue(String key, String value, String meta, ZonedDateTime at) {
    }
}
