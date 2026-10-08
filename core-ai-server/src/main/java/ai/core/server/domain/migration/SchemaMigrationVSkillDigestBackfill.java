package ai.core.server.domain.migration;

import ai.core.server.skill.SkillDigest;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backfills {@code digest} for skill documents created before the field existed
 * (uploads, repo registrations and the preset-skills seed all pre-date it) and adds
 * the plain {@code digest} index. Iteration pages over every skill by {@code _id}
 * (indexed, notablescan-safe) and only touches documents that still lack the field,
 * so re-runs are no-ops. Content is small (≤10MB per skill, hundreds of skills).
 *
 * @author stephen
 */
public class SchemaMigrationVSkillDigestBackfill implements SchemaMigration {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaMigrationVSkillDigestBackfill.class);
    private static final int PAGE_SIZE = 100;

    @Override
    public String version() {
        return "20260904002";
    }

    @Override
    public String description() {
        return "backfill skills.digest and add digest index";
    }

    @Override
    public void migrate(Mongo mongo) {
        Object lastId = new org.bson.types.MinKey();
        long total = 0;
        while (true) {
            var page = findPage(mongo, lastId);
            if (page.isEmpty()) break;
            lastId = page.getLast().get("_id");
            total += updateBatch(mongo, page);
        }
        mongo.createIndex("skills", Indexes.ascending("digest"), new IndexOptions().name("digest"));
        LOGGER.info("skill digest migration completed: backfilled {} documents", total);
    }

    private List<Document> findPage(Mongo mongo, Object lastId) {
        var result = mongo.runCommand(new Document("find", "skills")
            .append("filter", new Document("_id", new Document("$gt", lastId)))
            .append("sort", new Document("_id", 1))
            .append("projection", new Document("content", 1).append("resources", 1).append("digest", 1))
            .append("batchSize", PAGE_SIZE)
            .append("limit", PAGE_SIZE));
        var cursor = (Document) result.get("cursor");
        return cursor.getList("firstBatch", Document.class);
    }

    @SuppressWarnings("unchecked")
    private int updateBatch(Mongo mongo, List<Document> page) {
        var updates = new ArrayList<Document>();
        for (var doc : page) {
            if (doc.get("digest") != null) continue;   // idempotent: never recompute existing digests
            var resources = resourceBytes(doc);
            if (resources == null) {
                LOGGER.warn("skipping digest backfill for a skill whose resources live in object storage, id={}", doc.get("_id"));
                continue;
            }
            var digest = SkillDigest.of(doc.getString("content"), resources);
            updates.add(new Document("q", new Document("_id", doc.get("_id")))
                .append("u", new Document("$set", new Document("digest", digest))));
        }
        if (updates.isEmpty()) return 0;
        var result = mongo.runCommand(new Document("update", "skills").append("updates", updates));
        return ((Number) result.get("n")).intValue();
    }

    /** Null when any resource is stored as a blob — the bytes are not in this document. */
    @SuppressWarnings("unchecked")
    private Map<String, byte[]> resourceBytes(Document doc) {
        var result = new LinkedHashMap<String, byte[]>();
        var rawResources = (List<Document>) doc.get("resources");
        if (rawResources == null) return result;
        for (var raw : rawResources) {
            if (raw.getString("storage_path") != null) return null;
            var path = raw.getString("path");
            var content = raw.getString("content");
            result.put(path == null ? "" : path, (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        }
        return result;
    }
}
