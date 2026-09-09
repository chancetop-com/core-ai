package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;
import org.bson.BsonNull;
import org.bson.Document;

import java.util.List;

/**
 * Paged report directory: file attribution rows carry the file's own {@code created_at} ({@code target_created_at})
 * so the subject / project report lists sort and page on the attribution table alone instead of loading every
 * artifact of the project. Backfills legacy file rows from {@code file_records} (one server-side
 * {@code $lookup} + {@code $merge}) and adds the two sort indexes the paged queries use.
 * <p>
 * Match uses null equality, not {@code $exists:false}: the shared clusters run {@code notablescan} and only the
 * equality form is served by the (target_type, target_id) index prefix.
 *
 * @author stephen
 */
public class SchemaMigrationVProjectAttributionFileTime implements SchemaMigration {
    static final String COLLECTION = "project_subject_attributions";

    @Override
    public String version() {
        return "20260909002";
    }

    @Override
    public String description() {
        return "project attributions: backfill file rows' target_created_at from file_records, add (project|subject, target_type, target_created_at) indexes";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.runCommand(new Document("aggregate", COLLECTION)
            .append("pipeline", List.of(
                new Document("$match", new Document("target_type", "file").append("target_created_at", BsonNull.VALUE)),
                new Document("$lookup", new Document("from", "file_records")
                    .append("localField", "target_id").append("foreignField", "_id")
                    .append("pipeline", List.of(new Document("$project", new Document("created_at", 1))))
                    .append("as", "file")),
                new Document("$unwind", "$file"),
                new Document("$project", new Document("_id", 1).append("target_created_at", "$file.created_at")),
                new Document("$merge", new Document("into", COLLECTION).append("on", "_id")
                    .append("whenMatched", "merge").append("whenNotMatched", "discard"))))
            .append("cursor", new Document()));
        // rows whose file is gone keep a null time and sort last; the report list drops them anyway (no file record)
        mongo.createIndex(COLLECTION, Indexes.compoundIndex(Indexes.ascending("project_id"), Indexes.ascending("target_type"), Indexes.descending("target_created_at")));
        mongo.createIndex(COLLECTION, Indexes.compoundIndex(Indexes.ascending("subject_id"), Indexes.ascending("target_type"), Indexes.descending("target_created_at")));
    }
}
