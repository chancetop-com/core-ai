package ai.core.server.domain.migration;

import ai.core.server.project.ProjectBuiltinAgents;
import core.framework.mongo.Mongo;
import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Creates the three builtin definitions behind the project agent (one AGENT investigator + two
 * LLM_CALL writers) so analysis prompts and response schemas are user-tunable in the UI instead
 * of hardcoded. Upserts are $setOnInsert — user edits survive restarts; the admin reset endpoint
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
        return "create builtin project-agent, project-attributor and project-subject-analyzer definitions";
    }

    @Override
    public void migrate(Mongo mongo) {
        var now = Date.from(Instant.now());
        upsert(mongo, ProjectBuiltinAgents.mainAgentDoc(now));
        upsert(mongo, ProjectBuiltinAgents.writerDoc("builtin-" + ProjectBuiltinAgents.ATTRIBUTOR, ProjectBuiltinAgents.ATTRIBUTOR,
            "Attributes the targets listed in the query to project subjects. Prepare the query as the SUBJECTS list plus a digest of the unattributed targets; the result is applied to the attribution table automatically.",
            ProjectBuiltinAgents.ATTRIBUTOR_PROMPT, ProjectBuiltinAgents.attributionSchema(), now));
        upsert(mongo, ProjectBuiltinAgents.writerDoc("builtin-" + ProjectBuiltinAgents.SUBJECT_ANALYZER, ProjectBuiltinAgents.SUBJECT_ANALYZER,
            "Derives ONE subject's status/KPIs/action items/notes from the query (playbook + subject context + current state + material digest) and applies them automatically; pass the subject_id.",
            ProjectBuiltinAgents.subjectAnalyzerPrompt(), ProjectBuiltinAgents.subjectAnalysisSchema(), now));
    }

    private void upsert(Mongo mongo, Document doc) {
        var filter = new Document("_id", doc.getString("_id"));
        var update = new Document("$setOnInsert", doc);
        mongo.runCommand(new Document("update", "agents")
            .append("updates", List.of(new Document("q", filter).append("u", update).append("upsert", Boolean.TRUE))));
    }
}
