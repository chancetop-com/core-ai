package ai.core.server.domain.migration;

import ai.core.server.workflow.WorkflowTestModule;
import com.mongodb.MongoCommandException;
import core.framework.inject.Inject;
import core.framework.mongo.Mongo;
import core.framework.test.Context;
import core.framework.test.IntegrationExtension;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real migration against a real Mongo. The shared clusters run {@code notablescan}, so every scan the
 * migration issues must be index-served — a mocked Mongo accepts any filter, which is how the unassigned-row
 * scan shipped broken (error 291 at startup).
 *
 * @author stephen
 */
@EnabledIf("mongoReachable")
@ExtendWith(IntegrationExtension.class)
@Context(module = WorkflowTestModule.class)
class SchemaMigrationVProjectAttributionScopeMongoTest {
    private static final String COLLECTION = "project_subject_attributions";
    private static final String SUBJECTS = "project_subjects";
    private static final String RUN = Long.toHexString(System.nanoTime());
    private static final String PROJECT_ID = "mig-project-" + RUN;
    private static final String SUBJECT_1 = "mig-subject-1-" + RUN;
    private static final String SUBJECT_2 = "mig-subject-2-" + RUN;
    private static final String FILE_1 = "mig-file-1-" + RUN;
    private static final String FILE_2 = "mig-file-2-" + RUN;
    private static final String SESSION_1 = "mig-session-1-" + RUN;
    private static final String ORPHAN = "mig-orphan-" + RUN;

    static boolean mongoReachable() {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 27017), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Inject
    Mongo mongo;

    @BeforeEach
    void resetCollection() {
        // the migration is only ever run against a collection without its indexes; leftovers from a previous
        // run would change the backfill's $merge behavior (it silently stops on a unique index conflict)
        try {
            mongo.runCommand(new Document("drop", COLLECTION));
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != 26) throw e;   // NamespaceNotFound on a fresh database
        }
    }

    @AfterEach
    void cleanup() {
        // by _id: wftest carries no subject_id index and the local Mongo rejects collection scans
        delete(COLLECTION, List.of(FILE_1, FILE_2, SESSION_1, ORPHAN));
        delete(SUBJECTS, List.of(SUBJECT_1, SUBJECT_2));
    }

    @Test
    void migratesLegacyRowsWithoutCollectionScan() {
        seed();

        new SchemaMigrationVProjectAttributionScope().migrate(mongo);

        var rows = rows();
        assertEquals(List.of(FILE_1, SESSION_1), rows.stream().map(r -> r.getString("_id")).sorted().toList());
        assertEquals(PROJECT_ID, row(rows, FILE_1).getString("project_id"));
        assertEquals(PROJECT_ID, row(rows, SESSION_1).getString("project_id"));
        assertNull(row(rows, FILE_2), "the later duplicate home must be collapsed");
        assertNull(row(rows, ORPHAN), "rows whose subject is gone must be dropped");

        var indexes = indexNames();
        assertTrue(indexes.contains("project_id_1_target_id_1"), indexes.toString());
        assertTrue(indexes.contains("project_id_1_target_type_1"), indexes.toString());
        assertTrue(indexes.contains("target_type_1_target_id_1"), indexes.toString());
    }

    private void seed() {
        // legacy shape: attributions carry no project_id at all
        mongo.runCommand(new Document("insert", SUBJECTS).append("documents", List.of(
            new Document("_id", SUBJECT_1).append("project_id", PROJECT_ID).append("name", "one"),
            new Document("_id", SUBJECT_2).append("project_id", PROJECT_ID).append("name", "two"))));
        mongo.runCommand(new Document("insert", COLLECTION).append("documents", List.of(
            new Document("_id", FILE_1).append("subject_id", SUBJECT_1).append("target_type", "file")
                .append("target_id", "f-1").append("created_at", new Date(1000)),
            new Document("_id", FILE_2).append("subject_id", SUBJECT_2).append("target_type", "file")
                .append("target_id", "f-1").append("created_at", new Date(2000)),
            new Document("_id", SESSION_1).append("subject_id", SUBJECT_1).append("target_type", "session")
                .append("target_id", "c-1").append("created_at", new Date(1000)),
            new Document("_id", ORPHAN).append("subject_id", "mig-ghost-" + RUN).append("target_type", "file")
                .append("target_id", "f-9").append("created_at", new Date(1000)))));
    }

    private void delete(String collection, List<String> ids) {
        mongo.runCommand(new Document("delete", collection)
            .append("deletes", List.of(new Document("q", new Document("_id", new Document("$in", ids))).append("limit", 0))));
    }

    private List<Document> rows() {
        var result = mongo.runCommand(new Document("find", COLLECTION)
            .append("filter", new Document("_id", new Document("$in", List.of(FILE_1, FILE_2, SESSION_1, ORPHAN))))
            .append("projection", new Document("project_id", 1).append("target_type", 1).append("target_id", 1)));
        return ((Document) result.get("cursor")).getList("firstBatch", Document.class);
    }

    private Document row(List<Document> rows, String id) {
        return rows.stream().filter(r -> id.equals(r.getString("_id"))).findFirst().orElse(null);
    }

    private List<String> indexNames() {
        var result = mongo.runCommand(new Document("listIndexes", COLLECTION));
        var names = new ArrayList<String>();
        for (var index : ((Document) result.get("cursor")).getList("firstBatch", Document.class)) names.add(index.getString("name"));
        return names;
    }
}
