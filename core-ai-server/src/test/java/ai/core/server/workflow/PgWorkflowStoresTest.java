package ai.core.server.workflow;

import ai.core.server.domain.ArtifactRef;
import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TriggerType;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowDefinitionStatus;
import ai.core.server.domain.WorkflowMode;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.domain.WorkflowVersionStatus;
import ai.core.server.domain.WorkflowVisibility;
import core.framework.internal.db.DatabaseImpl;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings({"REC_CATCH_EXCEPTION", "NAB_NEEDLESS_BOOLEAN_CONSTANT_CONVERSION"})
class PgWorkflowStoresTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("workflow-store-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgWorkflowDefinitionRow.class);
        database.view(PgWorkflowDefinitionCountRow.class);
        database.view(PgWorkflowVersionRow.class);
        database.view(PgWorkflowRunRow.class);
        database.view(PgWorkflowRunCountRow.class);
        database.view(PgWorkflowNodeRunRow.class);
        createTables();
        return database;
    }

    private static void createTables() {
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS app");
            statement.execute("CREATE TABLE IF NOT EXISTS app.workflow_definitions ("
                + "id text PRIMARY KEY, user_id text, name text, mode text, draft_graph text, visibility text, "
                + "status text, published_version_id text, published_version integer, created_at timestamptz, "
                + "updated_at timestamptz, archived_at timestamptz, archived_by text, data jsonb NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS app.workflow_published_versions ("
                + "id text PRIMARY KEY, workflow_id text NOT NULL, version integer, preview boolean, status text, "
                + "sha256 text, published_by text, published_at timestamptz, data jsonb NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS app.workflow_runs ("
                + "id text PRIMARY KEY, workflow_id text NOT NULL, version_id text, user_id text, mode text, "
                + "session_id text, triggered_by text, preview boolean, parent_run_id text, status text, "
                + "visibility text, claimed_by text, lease_until timestamptz, started_at timestamptz, "
                + "completed_at timestamptz, created_at timestamptz, output text, error text, artifacts jsonb, "
                + "data jsonb NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS app.workflow_node_runs ("
                + "id text PRIMARY KEY, run_id text NOT NULL, workflow_id text, node_id text, node_type text, "
                + "scope_path_key text, status text, input_json text, output text, child_run_id text, preview boolean, "
                + "error text, started_at timestamptz, completed_at timestamptz, created_at timestamptz, "
                + "artifacts jsonb, chosen_edge_ids jsonb, data jsonb NOT NULL)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS workflow_node_runs_test_unique "
                + "ON app.workflow_node_runs (run_id, node_id, scope_path_key)");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize workflow test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    @BeforeEach
    void clearTables() {
        DATABASE.execute("DELETE FROM app.workflow_node_runs");
        DATABASE.execute("DELETE FROM app.workflow_runs");
        DATABASE.execute("DELETE FROM app.workflow_published_versions");
        DATABASE.execute("DELETE FROM app.workflow_definitions");
    }

    @Test
    void definitionCrudAndListFilters() {
        var store = new PgWorkflowDefinitionStore();
        store.database = DATABASE;
        var definition = definition("wf-1", "owner", "Public Flow");
        definition.visibility = WorkflowVisibility.PUBLIC;
        definition.publishedVersionId = "wf-1:v1";
        definition.publishedVersion = 1;
        definition.draftGraph = "{\"nodes\":[]}";
        store.insert(definition);

        assertEquals("Public Flow", store.get("wf-1").orElseThrow().name);
        assertEquals(1, store.listActive("owner", true, null, 0, 20).size());
        assertEquals(1, store.listActive("viewer", false, "Public", 0, 20).size());
        assertEquals(1, store.exploreCount("viewer", null));
        assertEquals(1, store.findByIds(List.of("wf-1")).size());

        definition.status = WorkflowDefinitionStatus.ARCHIVED;
        definition.archivedBy = "owner";
        definition.archivedAt = ZonedDateTime.now();
        store.replace(definition);
        assertEquals(0, store.countActive("owner", true, null));
        assertEquals(1, store.countArchived("owner", null));

        store.delete("wf-1");
        assertTrue(store.get("wf-1").isEmpty());
    }

    @Test
    void versionListAndExpiredPreviewCleanup() {
        var store = new PgWorkflowVersionStore();
        store.database = DATABASE;
        var v1 = version("wf-1:v1", 1, false);
        var preview = version("wf-1:preview:1", 0, true);
        preview.publishedAt = ZonedDateTime.now().minusDays(2);
        store.insert(v1);
        store.insert(preview);

        assertEquals(1, store.listNonPreview("wf-1").size());
        assertEquals(2, store.listAll("wf-1").size());
        assertEquals(1, store.deletePreviewOlderThan(ZonedDateTime.now().minusDays(1)));
        assertTrue(store.get(preview.id).isEmpty());
    }

    @Test
    void runLeaseLifecycleAndProjectQueries() {
        var store = new PgWorkflowRunStore();
        store.database = DATABASE;
        var now = ZonedDateTime.now();
        var run = run("run-1", "wf-1", "owner", now);
        run.startedAt = now;
        run.input = "{\"q\":1}";
        store.insert(run);

        assertEquals(1, store.findClaimable(now.plusSeconds(1)).size());
        assertTrue(store.claim("run-1", "worker-1", now, now.plusSeconds(60)));
        assertFalse(store.claim("run-1", "worker-2", now, now.plusSeconds(60)));
        assertTrue(store.renewLease("run-1", "worker-1", now.plusSeconds(120)));
        assertTrue(store.pause("run-1", "worker-1"));
        assertEquals(RunStatus.PAUSED, store.get("run-1").orElseThrow().status);
        assertTrue(store.wakePaused("run-1", now));
        assertTrue(store.claim("run-1", "worker-1", now, now.plusSeconds(60)));
        assertTrue(store.terminate("run-1", "worker-1",
            new WorkflowRunStore.RunTermination(RunStatus.COMPLETED, null, "{\"ok\":true}",
                List.of(artifact()), now.plusSeconds(1))));
        assertEquals(RunStatus.COMPLETED, store.get("run-1").orElseThrow().status);
        assertEquals("{\"ok\":true}", store.get("run-1").orElseThrow().output);
        assertFalse(store.terminate("run-1", "worker-1",
            new WorkflowRunStore.RunTermination(RunStatus.FAILED, "x", null, null, now)));

        var query = new WorkflowRunQuery();
        query.workflowIds = List.of("wf-1");
        query.ids = List.of("run-1");
        query.excludeIds = List.of();
        assertEquals(1, store.find(query).size());
        assertEquals(1, store.count(query));
        assertEquals(1, store.findByIds(List.of("run-1")).size());
        assertEquals(1, store.listByWorkflow("wf-1", "owner", false).size());
    }

    @Test
    void nodeRunInsertIsIdempotentAndSettlesWaiting() {
        var store = new PgWorkflowNodeRunStore();
        store.database = DATABASE;
        var nodeRun = nodeRun("run-1|human||", "run-1", "human", NodeRunStatus.WAITING);
        assertTrue(store.insertIfAbsent(nodeRun));
        assertFalse(store.insertIfAbsent(nodeRun));

        assertEquals(1, store.findWaitingByRun("run-1").size());
        assertTrue(store.settleWaiting("run-1", "human", NodeRunStatus.COMPLETED, "{\"approved\":true}",
            List.of("approve"), ZonedDateTime.now()));
        var settled = store.get(nodeRun.id).orElseThrow();
        assertEquals(NodeRunStatus.COMPLETED, settled.status);
        assertEquals(List.of("approve"), settled.chosenEdgeIds);

        var running = nodeRun("run-1|agent||", "run-1", "agent", NodeRunStatus.RUNNING);
        store.insertIfAbsent(running);
        assertEquals(1, store.resetRunningByRun("run-1", ZonedDateTime.now()));
        assertEquals(NodeRunStatus.FAILED_RETRYABLE, store.get(running.id).orElseThrow().status);
    }

    private WorkflowDefinition definition(String id, String userId, String name) {
        var definition = new WorkflowDefinition();
        definition.id = id;
        definition.userId = userId;
        definition.name = name;
        definition.mode = WorkflowMode.WORKFLOW;
        definition.status = WorkflowDefinitionStatus.ACTIVE;
        definition.visibility = WorkflowVisibility.PRIVATE;
        definition.createdAt = ZonedDateTime.now();
        definition.updatedAt = ZonedDateTime.now();
        return definition;
    }

    private WorkflowPublishedVersion version(String id, int number, boolean preview) {
        var version = new WorkflowPublishedVersion();
        version.id = id;
        version.workflowId = "wf-1";
        version.version = number;
        version.preview = preview;
        version.status = WorkflowVersionStatus.ACTIVE;
        version.graph = "{\"nodes\":[]}";
        version.sha256 = "sha";
        version.publishedAt = ZonedDateTime.now();
        return version;
    }

    private WorkflowRun run(String id, String workflowId, String userId, ZonedDateTime now) {
        var run = new WorkflowRun();
        run.id = id;
        run.workflowId = workflowId;
        run.userId = userId;
        run.mode = WorkflowMode.WORKFLOW;
        run.triggeredBy = TriggerType.API;
        run.status = RunStatus.PENDING;
        run.visibility = WorkflowVisibility.PRIVATE;
        run.leaseUntil = now;
        run.createdAt = now;
        return run;
    }

    private WorkflowNodeRun nodeRun(String id, String runId, String nodeId, NodeRunStatus status) {
        var nodeRun = new WorkflowNodeRun();
        nodeRun.id = id;
        nodeRun.runId = runId;
        nodeRun.workflowId = "wf-1";
        nodeRun.nodeId = nodeId;
        nodeRun.nodeType = "HUMAN_INPUT";
        nodeRun.scopePathKey = "";
        nodeRun.status = status;
        nodeRun.createdAt = ZonedDateTime.now();
        return nodeRun;
    }

    private ArtifactRef artifact() {
        var artifact = new ArtifactRef();
        artifact.fileName = "result.txt";
        return artifact;
    }
}
