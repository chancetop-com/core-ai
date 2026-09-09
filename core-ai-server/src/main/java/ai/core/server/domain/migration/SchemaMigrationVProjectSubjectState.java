package ai.core.server.domain.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;
import org.bson.Document;
import org.bson.types.MinKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * Project v1.5: the project document becomes a pure scaffold and the attribution stage convergent.
 * <ol>
 *   <li>indexes: unique scan markers, the analysis job gate on (status, last_analysis_at)</li>
 *   <li>subject current state moves from the project's embedded arrays onto the subject documents
 *       (subject_statuses → phase/summary, action_items → per-subject list); kpis/notes are dropped
 *       from the project (their history already lives in project_subject_events since v1.4)</li>
 *   <li>the old FUTURE (2099) "backfill complete" sentinel becomes a forward cursor at now; the
 *       unused analysis_run_id field is removed</li>
 *   <li>existing session/run/workflow-run attributions get scan markers (material time = attribution
 *       time) so the attributor does not re-offer them</li>
 *   <li>the retired builtin project-agent investigator definition is deleted</li>
 * </ol>
 * Every step is idempotent (already-moved projects have no arrays left; marker inserts ignore duplicates).
 *
 * @author stephen
 */
public class SchemaMigrationVProjectSubjectState implements SchemaMigration {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaMigrationVProjectSubjectState.class);
    private static final int PAGE_SIZE = 200;
    private static final Date FUTURE_SENTINEL = Date.from(Instant.parse("2099-01-01T00:00:00Z"));

    @Override
    public String version() {
        return "20260909001";
    }

    @Override
    public String description() {
        return "project v1.5: subject state onto subjects, scan markers + indexes, forward attribution cursor, drop project-agent";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("project_target_scans",
            Indexes.compoundIndex(Indexes.ascending("project_id"), Indexes.ascending("target_type"), Indexes.ascending("target_id")),
            new IndexOptions().unique(true));
        mongo.createIndex("projects", Indexes.compoundIndex(Indexes.ascending("status"), Indexes.ascending("last_analysis_at")));
        int projects = moveProjectState(mongo);
        int markers = seedScanMarkers(mongo);
        mongo.runCommand(new Document("delete", "agents")
            .append("deletes", List.of(new Document("q", new Document("_id", "builtin-project-agent")).append("limit", 1))));
        LOGGER.info("project subject state migration completed: projects moved={}, scan markers seeded={}", projects, markers);
    }

    private int moveProjectState(Mongo mongo) {
        int moved = 0;
        Object lastId = new MinKey();
        while (true) {
            var page = page(mongo, "projects", new Document(), lastId,
                new Document("subject_statuses", 1).append("action_items", 1).append("attribution_backfilled_at", 1)
                    .append("kpis", 1).append("notes", 1).append("analysis_run_id", 1));
            if (page.isEmpty()) break;
            lastId = page.getLast().get("_id");
            for (var project : page) {
                if (moveOne(mongo, project)) moved++;
            }
        }
        return moved;
    }

    private boolean moveOne(Mongo mongo, Document project) {
        var projectId = project.getString("_id");
        var subjectUpdates = new ArrayList<Document>();
        for (var status : listOf(project, "subject_statuses")) {
            var subjectId = status.getString("subject_id");
            if (subjectId == null) continue;
            var set = new Document();
            if (status.get("phase") != null) set.append("phase", status.get("phase"));
            if (status.get("summary") != null) set.append("summary", status.get("summary"));
            if (status.get("updated_at") != null) set.append("status_updated_at", status.get("updated_at"));
            if (status.get("updated_by") != null) set.append("status_updated_by", status.get("updated_by"));
            if (!set.isEmpty()) subjectUpdates.add(new Document("q", new Document("_id", subjectId)).append("u", new Document("$set", set)));
        }
        var itemsBySubject = new HashMap<String, List<Document>>();
        for (var item : listOf(project, "action_items")) {
            var subjectId = item.getString("subject_id");
            if (subjectId == null) continue;
            itemsBySubject.computeIfAbsent(subjectId, k -> new ArrayList<>()).add(item);
        }
        for (var entry : itemsBySubject.entrySet()) {
            subjectUpdates.add(new Document("q", new Document("_id", entry.getKey()))
                .append("u", new Document("$set", new Document("action_items", entry.getValue()))));
        }
        if (!subjectUpdates.isEmpty()) {
            mongo.runCommand(new Document("update", "project_subjects").append("updates", subjectUpdates));
        }
        var unset = new Document("subject_statuses", "").append("action_items", "").append("kpis", "").append("notes", "").append("analysis_run_id", "");
        var update = new Document("$unset", unset);
        var cursor = project.getDate("attribution_backfilled_at");
        if (cursor != null && !cursor.before(FUTURE_SENTINEL)) {
            update.append("$set", new Document("attribution_backfilled_at", new Date()));
        }
        mongo.runCommand(new Document("update", "projects").append("updates", List.of(
            new Document("q", new Document("_id", projectId)).append("u", update))));
        return !subjectUpdates.isEmpty();
    }

    // one marker per (project, target) for the non-file attribution rows; duplicates (already
    // seeded, or the same target attributed to several subjects) are reported as writeErrors, not thrown
    private int seedScanMarkers(Mongo mongo) {
        int total = 0;
        Object lastId = new MinKey();
        while (true) {
            var page = page(mongo, "project_subject_attributions", new Document("target_type", new Document("$in", List.of("session", "run", "workflow_run"))), lastId,
                new Document("project_id", 1).append("target_type", 1).append("target_id", 1).append("created_at", 1));
            if (page.isEmpty()) break;
            lastId = page.getLast().get("_id");
            var byKey = new HashMap<String, Document>();
            var now = new Date();
            for (var row : page) {
                var projectId = row.getString("project_id");
                var targetType = row.getString("target_type");
                var targetId = row.getString("target_id");
                if (projectId == null || targetType == null || targetId == null) continue;
                var at = row.getDate("created_at") != null ? row.getDate("created_at") : now;
                var key = projectId + "|" + targetType + "|" + targetId;
                var existing = byKey.get(key);
                if (existing != null && !existing.getDate("material_at").before(at)) continue;
                byKey.put(key, new Document("_id", UUID.randomUUID().toString())
                    .append("project_id", projectId)
                    .append("target_type", targetType)
                    .append("target_id", targetId)
                    .append("material_at", at)
                    .append("scanned_at", now));
            }
            if (byKey.isEmpty()) continue;
            var result = mongo.runCommand(new Document("insert", "project_target_scans")
                .append("documents", new ArrayList<>(byKey.values())).append("ordered", Boolean.FALSE));
            total += ((Number) result.get("n")).intValue();
        }
        return total;
    }

    // _id-range paging (index-served on notablescan clusters); the type filter narrows in place
    private List<Document> page(Mongo mongo, String collection, Document filter, Object lastId, Document projection) {
        var query = new Document(filter).append("_id", new Document("$gt", lastId));
        var result = mongo.runCommand(new Document("find", collection)
            .append("filter", query)
            .append("sort", new Document("_id", 1))
            .append("projection", projection)
            .append("batchSize", PAGE_SIZE)
            .append("limit", PAGE_SIZE));
        return ((Document) result.get("cursor")).getList("firstBatch", Document.class);
    }

    @SuppressWarnings("unchecked")
    private List<Document> listOf(Document doc, String field) {
        var value = doc.get(field);
        return value instanceof List<?> list ? (List<Document>) list : List.of();
    }
}
