package ai.core.server.domain.migration;

import ai.core.server.project.ProjectBuiltinAgents;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;
import org.bson.Document;
import org.bson.types.MinKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Auto subject discovery: the attributor now proposes new subjects, so its prompt/schema contract
 * changes (new_subjects is required, playbook/mode/rejected-list sections are read from the query).
 * <ol>
 *   <li>refresh the builtin project-attributor definition with $set — the old contract would leave
 *       new_subjects absent (the run then proposes nothing), and this is a definition the platform
 *       owns rather than user content</li>
 *   <li>backfill source=manual on existing subjects so "auto" is the only distinguished provenance
 *       (no auto_subjects backfill on projects: a missing value behaves as propose)</li>
 *   <li>partial unique index on (project_id, name_key) for source=auto only — manual subjects may
 *       collide in name, and an unindexed insert would let two concurrent runs create the same entity</li>
 * </ol>
 * Idempotent: the $set is a no-op on an unchanged document, the backfill skips rows that already
 * carry a source, and the index creation tolerates an identical existing index.
 *
 * @author stephen
 */
public class SchemaMigrationVProjectAutoSubjects implements SchemaMigration {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaMigrationVProjectAutoSubjects.class);
    private static final int PAGE_SIZE = 200;

    @Override
    public String version() {
        return "20260909004";
    }

    @Override
    public String description() {
        return "project auto subject discovery: refresh attributor definition, backfill subject source, partial unique index";
    }

    @Override
    public void migrate(Mongo mongo) {
        refreshAttributor(mongo);
        int backfilled = backfillSource(mongo);
        mongo.createIndex("project_subjects",
            Indexes.compoundIndex(Indexes.ascending("project_id"), Indexes.ascending("name_key")),
            new IndexOptions().unique(true)
                .partialFilterExpression(Filters.and(Filters.eq("source", "auto"), Filters.type("name_key", "string"))));
        LOGGER.info("project auto subject migration completed: subject sources backfilled={}", backfilled);
    }

    private void refreshAttributor(Mongo mongo) {
        var prompt = ProjectBuiltinAgents.attributorPrompt();
        var schema = ProjectBuiltinAgents.attributionSchema();
        var set = new Document("system_prompt", prompt)
            .append("response_schema", schema)
            .append("description", ProjectBuiltinAgents.ATTRIBUTOR_DESCRIPTION)
            .append("published_config", new Document("system_prompt", prompt).append("response_schema", schema))
            .append("updated_at", new Date());
        mongo.runCommand(new Document("update", "agents").append("updates", List.of(
            new Document("q", new Document("_id", "builtin-" + ProjectBuiltinAgents.ATTRIBUTOR)).append("u", new Document("$set", set)))));
    }

    // _id-range paging keeps the scan index-served on notablescan clusters ($exists:false alone is not)
    private int backfillSource(Mongo mongo) {
        int total = 0;
        Object lastId = new MinKey();
        while (true) {
            var page = page(mongo, lastId);
            if (page.isEmpty()) break;
            lastId = page.getLast().get("_id");
            var updates = new ArrayList<Document>();
            for (var subject : page) {
                if (subject.getString("source") != null) continue;
                updates.add(new Document("q", new Document("_id", subject.getString("_id")))
                    .append("u", new Document("$set", new Document("source", "manual"))));
            }
            if (updates.isEmpty()) continue;
            var result = mongo.runCommand(new Document("update", "project_subjects").append("updates", updates));
            total += ((Number) result.get("n")).intValue();
        }
        return total;
    }

    private List<Document> page(Mongo mongo, Object lastId) {
        var result = mongo.runCommand(new Document("find", "project_subjects")
            .append("filter", new Document("_id", new Document("$gt", lastId)))
            .append("sort", new Document("_id", 1))
            .append("projection", new Document("_id", 1).append("source", 1))
            .append("batchSize", PAGE_SIZE)
            .append("limit", PAGE_SIZE));
        return ((Document) result.get("cursor")).getList("firstBatch", Document.class);
    }
}
