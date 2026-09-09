package ai.core.server.domain.migration;

import ai.core.server.project.ProjectBuiltinAgents;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;
import org.bson.BsonNull;
import org.bson.Document;
import org.bson.types.MinKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Project attribution v2: one home per report.
 * <ol>
 *   <li>lookup indexes first — every scan below filters a field that must be index-served, the shared clusters
 *       run {@code notablescan} and reject collection scans with error 291</li>
 *   <li>denormalize {@code project_id} onto every attribution row (from its subject); rows whose subject is
 *       gone are dropped</li>
 *   <li>collapse duplicate FILE attributions inside a project (the attributor used to be allowed to home a
 *       report under several subjects): the earliest row wins</li>
 *   <li>the unique partial (project_id, target_id) file index — the invariant the query side relies on —
 *       created once the duplicates are gone; building it before the backfill would make that {@code $merge}
 *       stop silently at the first duplicate home</li>
 *   <li>cascade existing session/run attributions onto their artifacts, so subject report tabs keep their
 *       content when the query switches from "agent scope" to "file attribution"; parents attributed to
 *       several subjects of the same project are skipped (ambiguous)</li>
 *   <li>refresh the builtin attributor prompt (reports now get exactly one subject)</li>
 * </ol>
 *
 * @author stephen
 */
public class SchemaMigrationVProjectAttributionScope implements SchemaMigration {
    static final String COLLECTION = "project_subject_attributions";
    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaMigrationVProjectAttributionScope.class);
    private static final int PAGE_SIZE = 500;
    private static final int GROUP_BATCH = 10000;

    @Override
    public String version() {
        return "20260908003";
    }

    @Override
    public String description() {
        return "project attributions: backfill project_id, dedupe file homes, unique file index, cascade parents to artifacts, refresh attributor prompt";
    }

    @Override
    public void migrate(Mongo mongo) {
        createLookupIndexes(mongo);
        backfillProjectId(mongo);
        int removed = dedupeFiles(mongo);
        createFileHomeIndex(mongo);
        int cascaded = cascadeParents(mongo);
        refreshAttributorPrompt(mongo);
        LOGGER.info("project attribution scope migration completed: duplicates removed={}, artifacts cascaded={}", removed, cascaded);
    }

    // single server-side pass: attribution.subject_id -> project_subjects._id -> project_id, merged back by _id
    private void backfillProjectId(Mongo mongo) {
        // null equality, not $exists:false: only equality is index-served, and the shared clusters run notablescan
        mongo.runCommand(new Document("aggregate", COLLECTION)
            .append("pipeline", List.of(
                new Document("$match", new Document("project_id", BsonNull.VALUE)),
                new Document("$lookup", new Document("from", "project_subjects")
                    .append("localField", "subject_id").append("foreignField", "_id").append("as", "subject")),
                new Document("$unwind", "$subject"),
                new Document("$project", new Document("_id", 1).append("project_id", "$subject.project_id")),
                new Document("$merge", new Document("into", COLLECTION).append("on", "_id")
                    .append("whenMatched", "merge").append("whenNotMatched", "discard"))))
            .append("cursor", new Document()));
        // subject deleted underneath the row: nothing to attribute to any more
        mongo.runCommand(new Document("delete", COLLECTION)
            .append("deletes", List.of(new Document("q", new Document("project_id", BsonNull.VALUE)).append("limit", 0))));
    }

    @SuppressWarnings("unchecked")
    private int dedupeFiles(Mongo mongo) {
        var result = mongo.runCommand(new Document("aggregate", COLLECTION)
            .append("pipeline", List.of(
                new Document("$match", new Document("target_type", "file")),
                new Document("$group", new Document("_id", new Document("project_id", "$project_id").append("target_id", "$target_id"))
                    .append("rows", new Document("$push", new Document("id", "$_id").append("created_at", "$created_at")))
                    .append("count", new Document("$sum", 1))),
                new Document("$match", new Document("count", new Document("$gt", 1)))))
            .append("cursor", new Document("batchSize", GROUP_BATCH)));
        var groups = ((Document) result.get("cursor")).getList("firstBatch", Document.class);
        var toDelete = new ArrayList<Object>();
        for (var group : groups) {
            var rows = new ArrayList<>((List<Document>) group.get("rows"));
            rows.sort(Comparator.comparing((Document row) -> row.getDate("created_at"), Comparator.nullsLast(Comparator.naturalOrder())));
            for (int i = 1; i < rows.size(); i++) toDelete.add(rows.get(i).get("id"));
        }
        if (toDelete.isEmpty()) return 0;
        mongo.runCommand(new Document("delete", COLLECTION)
            .append("deletes", List.of(new Document("q", new Document("_id", new Document("$in", toDelete))).append("limit", 0))));
        return toDelete.size();
    }

    private void createLookupIndexes(Mongo mongo) {
        // before the scans below: both filter fields must be index-served (notablescan clusters reject collection scans)
        mongo.createIndex(COLLECTION, Indexes.compoundIndex(Indexes.ascending("target_type"), Indexes.ascending("target_id")));
        mongo.createIndex(COLLECTION, Indexes.compoundIndex(Indexes.ascending("project_id"), Indexes.ascending("target_type")));
    }

    private void createFileHomeIndex(Mongo mongo) {
        mongo.createIndex(COLLECTION, Indexes.compoundIndex(Indexes.ascending("project_id"), Indexes.ascending("target_id")),
            new IndexOptions().unique(true).partialFilterExpression(new Document("target_type", "file")));
    }

    private int cascadeParents(Mongo mongo) {
        int total = 0;
        for (var parentType : List.of("session", "run")) {
            Object lastId = new MinKey();
            while (true) {
                var page = findPage(mongo, parentType, lastId);
                if (page.isEmpty()) break;
                lastId = page.getLast().get("_id");
                total += cascadePage(mongo, parentType, page);
            }
        }
        return total;
    }

    private List<Document> findPage(Mongo mongo, String parentType, Object lastId) {
        var result = mongo.runCommand(new Document("find", COLLECTION)
            .append("filter", new Document("target_type", parentType).append("_id", new Document("$gt", lastId)))
            .append("sort", new Document("_id", 1))
            .append("projection", new Document("project_id", 1).append("subject_id", 1).append("target_id", 1))
            .append("batchSize", PAGE_SIZE)
            .append("limit", PAGE_SIZE));
        return ((Document) result.get("cursor")).getList("firstBatch", Document.class);
    }

    private int cascadePage(Mongo mongo, String parentType, List<Document> page) {
        // (project, parent) -> subjects; a parent spanning several subjects of one project is ambiguous
        var subjectsByKey = new LinkedHashMap<String, Set<String>>();
        var subjectByKey = new HashMap<String, String>();
        var projectByKey = new HashMap<String, String>();
        var parentIds = new HashSet<String>();
        for (var row : page) {
            var projectId = row.getString("project_id");
            var parentId = row.getString("target_id");
            if (projectId == null || parentId == null) continue;
            var key = projectId + "|" + parentId;
            subjectsByKey.computeIfAbsent(key, k -> new HashSet<>()).add(row.getString("subject_id"));
            subjectByKey.put(key, row.getString("subject_id"));
            projectByKey.put(key, projectId);
            parentIds.add(parentId);
        }
        if (parentIds.isEmpty()) return 0;
        var artifactsByParent = artifacts(mongo, "session".equals(parentType) ? "chat_sessions" : "agent_runs", parentIds);
        var inserts = new ArrayList<Document>();
        var now = Date.from(Instant.now());
        for (var entry : subjectsByKey.entrySet()) {
            if (entry.getValue().size() != 1) continue;
            var key = entry.getKey();
            var parentId = key.substring(key.indexOf('|') + 1);
            for (var fileId : artifactsByParent.getOrDefault(parentId, List.of())) {
                inserts.add(new Document("_id", UUID.randomUUID().toString())
                    .append("project_id", projectByKey.get(key))
                    .append("subject_id", subjectByKey.get(key))
                    .append("target_type", "file")
                    .append("target_id", fileId)
                    .append("source", "cascade")
                    .append("created_at", now));
            }
        }
        if (inserts.isEmpty()) return 0;
        // unordered: files already homed (unique index) are reported as writeErrors, not thrown
        var result = mongo.runCommand(new Document("insert", COLLECTION).append("documents", inserts).append("ordered", Boolean.FALSE));
        return ((Number) result.get("n")).intValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> artifacts(Mongo mongo, String collection, Set<String> parentIds) {
        var result = mongo.runCommand(new Document("find", collection)
            .append("filter", new Document("_id", new Document("$in", new ArrayList<>(parentIds))).append("artifacts.0", new Document("$exists", Boolean.TRUE)))
            .append("projection", new Document("artifacts.file_id", 1))
            .append("batchSize", PAGE_SIZE)
            .append("limit", PAGE_SIZE));
        var byParent = new HashMap<String, List<String>>();
        for (var doc : ((Document) result.get("cursor")).getList("firstBatch", Document.class)) {
            var fileIds = new ArrayList<String>();
            var artifacts = (List<Document>) doc.get("artifacts");
            if (artifacts == null) continue;
            for (var artifact : artifacts) {
                var fileId = artifact.getString("file_id");
                if (fileId != null && !fileId.isBlank() && !fileIds.contains(fileId)) fileIds.add(fileId);
            }
            byParent.put(doc.getString("_id"), fileIds);
        }
        return byParent;
    }

    private void refreshAttributorPrompt(Mongo mongo) {
        var prompt = ProjectBuiltinAgents.attributorPrompt();
        mongo.runCommand(new Document("update", "agents")
            .append("updates", List.of(new Document("q", new Document("_id", "builtin-" + ProjectBuiltinAgents.ATTRIBUTOR))
                .append("u", new Document("$set", new Document("system_prompt", prompt)
                    .append("published_config.system_prompt", prompt)
                    .append("updated_at", Date.from(Instant.now())))))));
    }
}
