package ai.core.server.domain.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;
import org.bson.Document;

/**
 * Index for the report-completion job scan: subjects with an in-flight report render
 * (report_run_id). Partial so the mostly-empty field does not bloat the index.
 *
 * @author stephen
 */
public class SchemaMigrationVProjectReportAgent implements SchemaMigration {
    @Override
    public String version() {
        return "20260820004";
    }

    @Override
    public String description() {
        return "create project_subjects(report_run_id) partial index for the report completion job";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("project_subjects", Indexes.ascending("report_run_id"),
            new IndexOptions().partialFilterExpression(new Document("report_run_id", new Document("$type", "string"))));
    }
}
