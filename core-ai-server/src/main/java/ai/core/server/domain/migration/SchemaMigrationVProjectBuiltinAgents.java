package ai.core.server.domain.migration;

import ai.core.server.project.ProjectBuiltinAgents;
import core.framework.mongo.Mongo;
import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Creates the two builtin LLM_CALL writer definitions behind the project analysis pipeline so
 * prompts and response schemas are user-tunable in the UI instead of hardcoded (the former
 * project-agent investigator was removed in v1.5). Upserts are $setOnInsert — user edits survive restarts; the admin reset endpoint
 * restores the defaults on demand.
 *
 * @author stephen
 */
public class SchemaMigrationVProjectBuiltinAgents implements SchemaMigration {
    @Override
    public String version() {
        return "20260813005";
    }

    @Override
    public String description() {
        return "create builtin project-attributor and project-subject-analyzer definitions";
    }

    @Override
    public void migrate(Mongo mongo) {
        var now = Date.from(Instant.now());
        upsert(mongo, ProjectBuiltinAgents.writerDoc("builtin-" + ProjectBuiltinAgents.ATTRIBUTOR, ProjectBuiltinAgents.ATTRIBUTOR,
            ProjectBuiltinAgents.ATTRIBUTOR_DESCRIPTION, ProjectBuiltinAgents.attributorPrompt(), ProjectBuiltinAgents.attributionSchema(), now));
        upsert(mongo, ProjectBuiltinAgents.writerDoc("builtin-" + ProjectBuiltinAgents.SUBJECT_ANALYZER, ProjectBuiltinAgents.SUBJECT_ANALYZER,
            ProjectBuiltinAgents.SUBJECT_ANALYZER_DESCRIPTION, ProjectBuiltinAgents.subjectAnalyzerPrompt(), ProjectBuiltinAgents.subjectAnalysisSchema(), now));
    }

    private void upsert(Mongo mongo, Document doc) {
        var filter = new Document("_id", doc.getString("_id"));
        var update = new Document("$setOnInsert", doc);
        mongo.runCommand(new Document("update", "agents")
            .append("updates", List.of(new Document("q", filter).append("u", update).append("upsert", Boolean.TRUE))));
    }
}
