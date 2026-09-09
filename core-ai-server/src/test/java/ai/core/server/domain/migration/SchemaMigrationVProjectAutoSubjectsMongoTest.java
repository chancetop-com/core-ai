package ai.core.server.domain.migration;

import ai.core.server.domain.ProjectSubject;
import ai.core.server.project.ProjectBuiltinAgents;
import ai.core.server.workflow.WorkflowTestModule;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Indexes;
import core.framework.inject.Inject;
import core.framework.mongo.Mongo;
import core.framework.mongo.MongoCollection;
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
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the auto-subject migration against a real Mongo: the attributor definition must be refreshed,
 * legacy subjects must gain source=manual and the partial unique index must reject a duplicate auto
 * name while leaving manual rows (and rows without a name key) unconstrained. A leftover index from a
 * previous run would make the duplicate assertions meaningless, so the fixture drops it around each test.
 *
 * @author stephen
 */
@EnabledIf("mongoReachable")
@ExtendWith(IntegrationExtension.class)
@Context(module = WorkflowTestModule.class)
class SchemaMigrationVProjectAutoSubjectsMongoTest {
    private static final String SUBJECTS = "project_subjects";
    private static final String AGENTS = "agents";
    private static final String INDEX = "project_id_1_name_key_1";
    private static final String RUN = Long.toHexString(System.nanoTime());
    private static final String PROJECT_ID = "mig-auto-project-" + RUN;
    private static final String USER_ID = "mig-auto-user-" + RUN;
    private static final String AGENT_ID = "builtin-" + ProjectBuiltinAgents.ATTRIBUTOR;
    private static final String LEGACY = "mig-auto-legacy-" + RUN;
    private static final String AUTO = "mig-auto-auto-" + RUN;
    private static final String AUTO_NO_KEY = "mig-auto-auto-nokey-" + RUN;
    private static final String MANUAL = "mig-auto-manual-" + RUN;
    private static final String MANUAL_TWIN = "mig-auto-manual-twin-" + RUN;
    private static final List<String> SUBJECT_IDS = List.of(LEGACY, AUTO, AUTO_NO_KEY, MANUAL, MANUAL_TWIN);

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
    @Inject
    MongoCollection<ProjectSubject> subjectCollection;

    @BeforeEach
    void reset() {
        deleteSubjects();
        dropNameKeyIndex();
    }

    @AfterEach
    void cleanup() {
        deleteSubjects();
        dropNameKeyIndex();
    }

    @Test
    void refreshesAttributorDefinition() {
        var previous = document(AGENTS, AGENT_ID);
        try {
            upsert(AGENTS, AGENT_ID, new Document("system_prompt", "stale").append("response_schema", "stale"));

            migrate();

            var agent = document(AGENTS, AGENT_ID);
            assertNotNull(agent);
            assertEquals(ProjectBuiltinAgents.attributorPrompt(), agent.getString("system_prompt"));
            assertEquals(ProjectBuiltinAgents.attributionSchema(), agent.getString("response_schema"));
            assertEquals(ProjectBuiltinAgents.ATTRIBUTOR_DESCRIPTION, agent.getString("description"));
            var published = (Document) agent.get("published_config");
            assertEquals(ProjectBuiltinAgents.attributorPrompt(), published.getString("system_prompt"));
            assertEquals(ProjectBuiltinAgents.attributionSchema(), published.getString("response_schema"));
        } finally {
            if (previous == null) delete(AGENTS, AGENT_ID);
            else replace(AGENTS, AGENT_ID, previous);
        }
    }

    @Test
    void backfillsSourceAndEnforcesUniqueAutoNames() {
        // two manual rows share a name key: the index is partial on source=auto, so creation must succeed
        insert(subject(LEGACY, null, null));
        insert(subject(AUTO, "auto", "acme"));
        insert(subject(MANUAL, "manual", "acme"));
        insert(subject(MANUAL_TWIN, "manual", "acme"));

        migrate();

        assertEquals("manual", document(SUBJECTS, LEGACY).getString("source"), "a legacy row must be backfilled");
        assertEquals("auto", document(SUBJECTS, AUTO).getString("source"), "an explicit source must survive");
        assertEquals("acme", document(SUBJECTS, AUTO).getString("name_key"));
        assertTrue(indexNames().contains(INDEX), indexNames().toString());

        var duplicate = subject(AUTO_NO_KEY, "auto", "acme");
        var error = assertThrows(MongoWriteException.class, () -> subjectCollection.insert(duplicate));
        assertEquals(11000, error.getCode());
        assertDoesNotThrow(() -> subjectCollection.insert(subject(AUTO_NO_KEY, "auto", null)),
            "an auto row without a name key is outside the partial index");
    }

    private void migrate() {
        new SchemaMigrationVProjectAutoSubjects().migrate(mongo);
    }

    private ProjectSubject subject(String id, String source, String nameKey) {
        var subject = new ProjectSubject();
        subject.id = id;
        subject.projectId = PROJECT_ID;
        subject.userId = USER_ID;
        subject.name = "mig-auto-subject-" + id;
        subject.source = source;
        subject.nameKey = nameKey;
        subject.createdAt = ZonedDateTime.now();
        subject.updatedAt = subject.createdAt;
        return subject;
    }

    private void insert(ProjectSubject subject) {
        subjectCollection.insert(subject);
    }

    private void deleteSubjects() {
        delete(SUBJECTS, new Document("$in", SUBJECT_IDS));
    }

    private void delete(String collection, String id) {
        mongo.runCommand(new Document("delete", collection)
            .append("deletes", List.of(new Document("q", new Document("_id", id)).append("limit", 0))));
    }

    private void delete(String collection, Document filter) {
        mongo.runCommand(new Document("delete", collection)
            .append("deletes", List.of(new Document("q", new Document("_id", filter)).append("limit", 0))));
    }

    private void upsert(String collection, String id, Document fields) {
        mongo.runCommand(new Document("update", collection).append("updates", List.of(
            new Document("q", new Document("_id", id))
                .append("u", new Document("$set", fields))
                .append("upsert", true))));
    }

    private void replace(String collection, String id, Document document) {
        mongo.runCommand(new Document("update", collection).append("updates", List.of(
            new Document("q", new Document("_id", id)).append("u", document))));
    }

    private Document document(String collection, String id) {
        var result = mongo.runCommand(new Document("find", collection)
            .append("filter", new Document("_id", id))
            .append("limit", 1));
        var batch = ((Document) result.get("cursor")).getList("firstBatch", Document.class);
        return batch.isEmpty() ? null : batch.getFirst();
    }

    private void dropNameKeyIndex() {
        mongo.dropIndex(SUBJECTS, Indexes.ascending("project_id", "name_key"));
    }

    private List<String> indexNames() {
        var result = mongo.runCommand(new Document("listIndexes", SUBJECTS));
        var names = new ArrayList<String>();
        for (var index : ((Document) result.get("cursor")).getList("firstBatch", Document.class)) names.add(index.getString("name"));
        return names;
    }
}
