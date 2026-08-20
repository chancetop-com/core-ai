package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * v1.4 event collection: creates the project_subject_events indexes and backfills the embedded
 * kpis[]/notes[]/subject_statuses[]/action_items[] arrays into append-only event rows so existing
 * projects get the same phase/KPI/action history the new write surface records going forward.
 * Projects with events_backfilled_at already set are skipped (crash-replay safety).
 *
 * @author stephen
 */
public class SchemaMigrationVProjectEvents implements SchemaMigration {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaMigrationVProjectEvents.class);
    private static final int PAGE_SIZE = 20;

    @Override
    public String version() {
        return "20260820001";
    }

    @Override
    public String description() {
        return "create project_subject_events indexes and backfill embedded state history into events";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("project_subject_events", Indexes.compoundIndex(Indexes.ascending("subject_id"), Indexes.ascending("at")));
        mongo.createIndex("project_subject_events", Indexes.compoundIndex(Indexes.ascending("project_id"), Indexes.ascending("at")));
        backfill(mongo);
    }

    private void backfill(Mongo mongo) {
        Object lastId = "";
        long total = 0;
        while (true) {
            var page = projectPage(mongo, lastId);
            if (page.isEmpty()) break;
            lastId = page.getLast().getString("_id");
            for (var project : page) {
                var docs = eventDocs(project);
                if (!docs.isEmpty()) {
                    mongo.runCommand(new Document("insert", "project_subject_events").append("documents", docs));
                    total += docs.size();
                }
                mongo.runCommand(new Document("update", "projects").append("updates", List.of(
                    new Document("q", new Document("_id", project.getString("_id")))
                        .append("u", new Document("$set", new Document("events_backfilled_at", new Date()))))));
            }
            LOGGER.info("project event backfill progress: processed {} events", total);
        }
        LOGGER.info("project event backfill completed: {} events", total);
    }

    private List<Document> projectPage(Mongo mongo, Object lastId) {
        var result = mongo.runCommand(new Document("find", "projects")
            .append("filter", new Document("$and", List.of(
                new Document("_id", new Document("$gt", lastId)),
                new Document("events_backfilled_at", new Document("$exists", Boolean.FALSE)))))
            .append("sort", new Document("_id", 1))
            .append("projection", new Document("kpis", 1).append("notes", 1)
                .append("subject_statuses", 1).append("action_items", 1))
            .append("batchSize", PAGE_SIZE)
            .append("limit", PAGE_SIZE));
        var cursor = (Document) result.get("cursor");
        return cursor.getList("firstBatch", Document.class);
    }

    private List<Document> eventDocs(Document project) {
        var projectId = project.getString("_id");
        var docs = new ArrayList<Document>();
        for (var kpi : listOf(project, "kpis")) {
            var doc = eventDoc(projectId, str(kpi, "subject_id"), "kpi", str(kpi, "value"), dateOf(kpi, "created_at"));
            finishEvent(doc, "key", str(kpi, "key"), kpi.get("unit") != null ? new Document("unit", kpi.get("unit")).toJson() : null, str(kpi, "created_by"));
            docs.add(doc);
        }
        for (var note : listOf(project, "notes")) {
            var doc = eventDoc(projectId, str(note, "subject_id"), "note", str(note, "content"), dateOf(note, "created_at"));
            finishEvent(doc, null, null, null, str(note, "created_by"));
            docs.add(doc);
        }
        for (var status : listOf(project, "subject_statuses")) {
            var phase = str(status, "phase");
            if (phase != null) {
                var doc = eventDoc(projectId, str(status, "subject_id"), "phase", phase, dateOf(status, "updated_at"));
                finishEvent(doc, "key", phase, null, str(status, "updated_by"));
                docs.add(doc);
            }
            var summary = str(status, "summary");
            if (summary != null) {
                var doc = eventDoc(projectId, str(status, "subject_id"), "summary", summary, dateOf(status, "updated_at"));
                finishEvent(doc, "key", phase, null, str(status, "updated_by"));
                docs.add(doc);
            }
        }
        for (var item : listOf(project, "action_items")) {
            var meta = new Document("title", str(item, "title"));
            if (item.get("note") != null) meta.append("note", item.get("note"));
            var at = item.get("updated_at") != null ? item.getDate("updated_at") : item.getDate("created_at");
            var doc = eventDoc(projectId, str(item, "subject_id"), "action_item", str(item, "status"), at);
            finishEvent(doc, "key", str(item, "id"), meta.toJson(), str(item, "updated_by"));
            docs.add(doc);
        }
        return docs;
    }

    private Document eventDoc(String projectId, String subjectId, String type, String value, Date at) {
        var doc = new Document("_id", UUID.randomUUID().toString())
            .append("project_id", projectId)
            .append("type", type)
            .append("value", value)
            .append("at", at != null ? at : new Date());
        if (subjectId != null) doc.append("subject_id", subjectId);
        return doc;
    }

    private void finishEvent(Document doc, String keyField, String key, String meta, String createdBy) {
        if (keyField != null && key != null) doc.append(keyField, key);
        if (meta != null) doc.append("meta", meta);
        doc.append("created_by", createdBy != null ? createdBy : "project-agent");
    }

    @SuppressWarnings("unchecked")
    private List<Document> listOf(Document doc, String field) {
        var value = doc.get(field);
        return value instanceof List<?> list ? (List<Document>) list : List.of();
    }

    private String str(Document doc, String field) {
        var value = doc.get(field);
        return value == null ? null : value.toString();
    }

    private Date dateOf(Document doc, String field) {
        return doc.getDate(field);
    }
}
