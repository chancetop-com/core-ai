package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes FILE attribution rows whose file no longer exists. The v2 cascade (20260908003) homed every artifact of
 * an attributed session/run, including artifacts whose {@code file_records} row had already expired under the old
 * 30-day TTL; those rows inflate the report counts ("1–17 of 36") while the list itself drops them. Server-side
 * {@code $lookup} finds the orphans; ids are paged through the cursor and deleted in batches.
 *
 * @author stephen
 */
public class SchemaMigrationVProjectAttributionPurgeDangling implements SchemaMigration {
    static final String COLLECTION = "project_subject_attributions";
    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaMigrationVProjectAttributionPurgeDangling.class);
    private static final int BATCH = 1000;

    @Override
    public String version() {
        return "20260909003";
    }

    @Override
    public String description() {
        return "project attributions: delete file rows whose file_records entry is gone (orphans of the v2 cascade over TTL-expired files)";
    }

    @Override
    public void migrate(Mongo mongo) {
        var result = mongo.runCommand(new Document("aggregate", COLLECTION)
            .append("pipeline", List.of(
                new Document("$match", new Document("target_type", "file")),
                new Document("$lookup", new Document("from", "file_records")
                    .append("localField", "target_id").append("foreignField", "_id")
                    .append("pipeline", List.of(new Document("$project", new Document("_id", 1))))
                    .append("as", "file")),
                new Document("$match", new Document("file", new Document("$size", 0))),
                new Document("$project", new Document("_id", 1))))
            .append("cursor", new Document("batchSize", BATCH)));
        int deleted = 0;
        var cursor = (Document) result.get("cursor");
        deleted += delete(mongo, ids(cursor.getList("firstBatch", Document.class)));
        long cursorId = cursor.get("id") instanceof Number number ? number.longValue() : 0L;
        while (cursorId != 0L) {
            var more = mongo.runCommand(new Document("getMore", cursorId).append("collection", COLLECTION).append("batchSize", BATCH));
            cursor = (Document) more.get("cursor");
            deleted += delete(mongo, ids(cursor.getList("nextBatch", Document.class)));
            cursorId = cursor.get("id") instanceof Number number ? number.longValue() : 0L;
        }
        LOGGER.info("dangling file attributions purged: {}", deleted);
    }

    private List<Object> ids(List<Document> docs) {
        var ids = new ArrayList<Object>(docs.size());
        for (var doc : docs) ids.add(doc.get("_id"));
        return ids;
    }

    private int delete(Mongo mongo, List<Object> ids) {
        if (ids.isEmpty()) return 0;
        var result = mongo.runCommand(new Document("delete", COLLECTION)
            .append("deletes", List.of(new Document("q", new Document("_id", new Document("$in", ids))).append("limit", 0))));
        return ((Number) result.get("n")).intValue();
    }
}
