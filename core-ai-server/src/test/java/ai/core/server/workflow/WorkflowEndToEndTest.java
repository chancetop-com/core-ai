package ai.core.server.workflow;

import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TriggerType;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowRun;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.test.Context;
import core.framework.test.IntegrationExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live end-to-end against a real Mongo: create draft -> publish (validate + sha256 + version) -> create run ->
 * the runner claims (CAS), loads the sha256-verified published graph, drives START -> END through the Mongo
 * journal, and finishes. Exercises every Mongo-coupled piece the unit tests stub out.
 *
 * @author Xander
 */
@EnabledIf("mongoReachable")   // skips gracefully (no Mongo connect attempt) when no local Mongo is up
@ExtendWith(IntegrationExtension.class)
@Context(module = WorkflowTestModule.class)
class WorkflowEndToEndTest {
    static boolean mongoReachable() {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 27017), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Inject
    WorkflowDefinitionService definitionService;

    @Inject
    WorkflowPublishService publishService;

    @Inject
    WorkflowRunService runService;

    @Inject
    WorkflowRunner runner;

    @Inject
    MongoCollection<WorkflowRun> runCollection;

    @Inject
    MongoCollection<WorkflowNodeRun> nodeRunCollection;

    @Test
    void startToEndRunDrivesToCompletedThroughMongo() {
        String graph = """
            {"nodes": [{"id": "start", "type": "START"}, {"id": "end", "type": "END"}],
             "edges": [{"id": "e0", "source": "start", "target": "end"}]}
            """;
        WorkflowDefinition definition = definitionService.create("e2e-workflow", "WORKFLOW", graph, "user-1");
        publishService.publish(definition.id, "user-1");

        WorkflowRun created = runService.createRun(definition.id, "{\"q\": \"hi\"}", TriggerType.API, "user-1");
        assertEquals(RunStatus.PENDING, created.status);

        boolean owned = runner.advance(created.id);   // CAS claim + load sha256-verified graph + drive + finish
        assertTrue(owned);

        WorkflowRun finished = runCollection.get(created.id).orElseThrow();
        assertEquals(RunStatus.COMPLETED, finished.status);
        assertNotNull(finished.completedAt);

        var nodeRuns = nodeRunCollection.find(Filters.eq("run_id", created.id));
        assertEquals(2, nodeRuns.size());   // start + end, persisted as node-runs
        assertTrue(nodeRuns.stream().allMatch(nodeRun -> nodeRun.status == NodeRunStatus.COMPLETED));

        // read-side endpoints the editor depends on
        assertTrue(definitionService.list("user-1").stream().anyMatch(d -> d.id.equals(definition.id)));
        assertTrue(definitionService.get(definition.id, "user-1").draftGraph.contains("START"));
        assertTrue(publishService.validate(definitionService.get(definition.id, "user-1")).isEmpty());
        assertTrue(runService.listRuns(definition.id, "user-1").stream().anyMatch(r -> r.id.equals(created.id)));
        assertEquals(2, runService.listNodeRuns(created.id, "user-1").size());
    }
}
