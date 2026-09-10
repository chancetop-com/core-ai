package ai.core.server.domain.migration;

import ai.core.server.project.ProjectBuiltinAgents;
import core.framework.mongo.Mongo;
import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Creates the playbook writer builtin definition (the LLM_CALL behind "generate playbook").
 * $setOnInsert — user edits survive restarts; the admin reset endpoint restores the default.
 *
 * @author stephen
 */
public class SchemaMigrationVProjectPlaybookWriter implements SchemaMigration {
    @Override
    public String version() {
        return "20260910002";
    }

    @Override
    public String description() {
        return "create builtin project-playbook-writer definition";
    }

    @Override
    public void migrate(Mongo mongo) {
        var now = Date.from(Instant.now());
        var doc = ProjectBuiltinAgents.playbookWriterDoc(now);
        mongo.runCommand(new Document("update", "agents")
            .append("updates", List.of(new Document("q", new Document("_id", doc.getString("_id")))
                .append("u", new Document("$setOnInsert", doc))
                .append("upsert", Boolean.TRUE))));
    }
}
