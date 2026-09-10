package ai.core.server.domain.migration;

import ai.core.server.project.ProjectBuiltinAgents;
import core.framework.mongo.Mongo;
import org.bson.Document;
import org.bson.types.MinKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Repairs the two defects shipped with auto subject discovery:
 * <ol>
 *   <li>the attributor's {@code response_schema} was written to the database with a root-level
 *       "required" nested inside "properties" and an unbalanced brace. Parsing it throws, so every
 *       attribution run failed and the whole stage stopped producing. The definition is refreshed
 *       with the fixed schema (prompt and published config with it) — the platform owns it, and the
 *       reset endpoint would restore the same content.</li>
 *   <li>the forward cursor was advanced to the newest record of the FASTEST target type (runs walk
 *       30 records per batch, sessions 20), dragging it over records that were never offered. They
 *       sit behind the cursor and outside the newest batch, so nothing would ever reach them again.
 *       Each project's cursor is rewound to its oldest scan marker: the next rounds walk that range
 *       once more, the markers keep the already-offered records cheap to skip and only the missed
 *       material costs an LLM run.</li>
 * </ol>
 * Idempotent: the {@code $set} is a no-op on an unchanged document, a cursor already at or before its
 * oldest marker is left alone, and a project without markers is skipped.
 *
 * @author stephen
 */
public class SchemaMigrationVProjectAttributionRepair implements SchemaMigration {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaMigrationVProjectAttributionRepair.class);
    private static final int PAGE_SIZE = 200;

    @Override
    public String version() {
        return "20260910001";
    }

    @Override
    public String description() {
        return "project attribution repair: refresh the malformed attributor schema, rewind cursors that ran past never-offered records";
    }

    @Override
    public void migrate(Mongo mongo) {
        refreshAttributor(mongo);
        int rewound = rewindCursors(mongo);
        LOGGER.info("project attribution repair completed: cursors rewound={}", rewound);
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
    private int rewindCursors(Mongo mongo) {
        int rewound = 0;
        Object lastId = new MinKey();
        while (true) {
            var page = page(mongo, lastId);
            if (page.isEmpty()) break;
            lastId = page.getLast().get("_id");
            var updates = new ArrayList<Document>();
            for (var project : page) {
                var id = project.getString("_id");
                var cursor = project.getDate("attribution_backfilled_at");
                if (cursor == null) continue;      // never walked: the next round starts from the beginning anyway
                var oldest = oldestMarker(mongo, id);
                if (oldest == null || !cursor.after(oldest)) continue;
                updates.add(new Document("q", new Document("_id", id))
                    .append("u", new Document("$set", new Document("attribution_backfilled_at", oldest))));
            }
            if (updates.isEmpty()) continue;
            var result = mongo.runCommand(new Document("update", "projects").append("updates", updates));
            rewound += ((Number) result.get("n")).intValue();
        }
        return rewound;
    }

    private List<Document> page(Mongo mongo, Object lastId) {
        var result = mongo.runCommand(new Document("find", "projects")
            .append("filter", new Document("_id", new Document("$gt", lastId)))
            .append("sort", new Document("_id", 1))
            .append("projection", new Document("_id", 1).append("attribution_backfilled_at", 1))
            .append("batchSize", PAGE_SIZE)
            .append("limit", PAGE_SIZE));
        return ((Document) result.get("cursor")).getList("firstBatch", Document.class);
    }

    private Date oldestMarker(Mongo mongo, String projectId) {
        var result = mongo.runCommand(new Document("find", "project_target_scans")
            .append("filter", new Document("project_id", projectId))
            .append("sort", new Document("material_at", 1))
            .append("projection", new Document("material_at", 1))
            .append("limit", 1));
        var batch = ((Document) result.get("cursor")).getList("firstBatch", Document.class);
        return batch.isEmpty() ? null : batch.getFirst().getDate("material_at");
    }
}
