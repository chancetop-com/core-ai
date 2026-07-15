package ai.core.server.workflow;

import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TriggerType;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.domain.WorkflowRun;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
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
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

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

    private static boolean awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
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
    WorkflowRunnerJob runnerJob;

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

    @Test
    void workflowNodeRunsChildWorkflowAndResumesParent() {
        WorkflowDefinition child = definitionService.create("child-workflow", "WORKFLOW", """
            {"nodes": [{"id": "start", "type": "START"}, {"id": "end", "type": "END"}],
             "edges": [{"id": "e0", "source": "start", "target": "end"}]}
            """, "user-1");
        WorkflowPublishedVersion childVersion = publishService.publish(child.id, "user-1");
        String parentGraph = """
            {"nodes": [
               {"id": "start", "type": "START"},
               {"id": "call_child", "type": "WORKFLOW",
                "config": {"source_workflow_id": "CHILD_ID", "version_id": "CHILD_VERSION",
                           "input_mappings": {"q": "{{ sys.input.q }}"}}},
               {"id": "end", "type": "END"}],
             "edges": [
               {"id": "e0", "source": "start", "target": "call_child"},
               {"id": "e1", "source": "call_child", "target": "end"}]}
            """.replace("CHILD_ID", child.id).replace("CHILD_VERSION", childVersion.id);
        WorkflowDefinition parent = definitionService.create("parent-workflow", "WORKFLOW", parentGraph, "user-1");
        publishService.publish(parent.id, "user-1");

        WorkflowRun parentRun = runService.createRun(parent.id, "{\"q\":\"hello\"}", TriggerType.API, "user-1");
        assertTrue(runner.advance(parentRun.id));
        WorkflowRun pausedParent = runCollection.get(parentRun.id).orElseThrow();
        assertEquals(RunStatus.PAUSED, pausedParent.status);

        WorkflowRun childRun = runCollection.find(Filters.eq("parent_run_id", parentRun.id)).getFirst();
        assertNotNull(childRun);
        assertEquals(1, childRun.depth);
        assertEquals("{\"q\":\"hello\"}", childRun.input);

        assertTrue(runner.advance(childRun.id));
        WorkflowRun wokenParent = runCollection.get(parentRun.id).orElseThrow();
        assertEquals(RunStatus.PENDING, wokenParent.status);

        assertTrue(runner.advance(parentRun.id));
        WorkflowRun finishedParent = runCollection.get(parentRun.id).orElseThrow();
        assertEquals(RunStatus.COMPLETED, finishedParent.status);
        assertEquals("{\"q\":\"hello\"}", finishedParent.output);
    }

    @Test
    void jobRecoversTerminalChildWorkflowWaitIfWakeWasMissed() {
        WorkflowDefinition child = definitionService.create("child-workflow-recovery", "WORKFLOW", """
            {"nodes": [{"id": "start", "type": "START"}, {"id": "end", "type": "END"}],
             "edges": [{"id": "e0", "source": "start", "target": "end"}]}
            """, "user-1");
        WorkflowPublishedVersion childVersion = publishService.publish(child.id, "user-1");
        String parentGraph = """
            {"nodes": [
               {"id": "start", "type": "START"},
               {"id": "call_child", "type": "WORKFLOW",
                "config": {"source_workflow_id": "CHILD_ID", "version_id": "CHILD_VERSION",
                           "input_mappings": {"q": "{{ sys.input.q }}"}}},
               {"id": "end", "type": "END"}],
             "edges": [
               {"id": "e0", "source": "start", "target": "call_child"},
               {"id": "e1", "source": "call_child", "target": "end"}]}
            """.replace("CHILD_ID", child.id).replace("CHILD_VERSION", childVersion.id);
        WorkflowDefinition parent = definitionService.create("parent-workflow-recovery", "WORKFLOW", parentGraph, "user-1");
        publishService.publish(parent.id, "user-1");

        WorkflowRun parentRun = runService.createRun(parent.id, "{\"q\":\"hello\"}", TriggerType.API, "user-1");
        assertTrue(runner.advance(parentRun.id));
        assertEquals(RunStatus.PAUSED, runCollection.get(parentRun.id).orElseThrow().status);

        WorkflowRun childRun = runCollection.find(Filters.eq("parent_run_id", parentRun.id)).getFirst();
        assertNotNull(childRun);

        var now = ZonedDateTime.now();
        var childEnd = new WorkflowNodeRun();
        childEnd.id = childRun.id + ":end";
        childEnd.runId = childRun.id;
        childEnd.workflowId = child.id;
        childEnd.nodeId = "end";
        childEnd.nodeType = "END";
        childEnd.scopePathKey = "";
        childEnd.status = NodeRunStatus.COMPLETED;
        childEnd.inputJson = "{\"q\":\"hello\"}";
        childEnd.output = "{\"q\":\"hello\"}";
        childEnd.startedAt = now;
        childEnd.completedAt = now;
        childEnd.createdAt = now;
        nodeRunCollection.insert(childEnd);
        runCollection.update(
            Filters.eq("_id", childRun.id),
            Updates.combine(Updates.set("status", RunStatus.COMPLETED), Updates.set("completed_at", now)));
        runCollection.update(
            Filters.eq("_id", parentRun.id),
            Updates.set("lease_until", now.minusSeconds(1)));

        runnerJob.execute(null);

        assertTrue(awaitUntil(() -> runCollection.get(parentRun.id).map(r -> r.status == RunStatus.COMPLETED).orElse(Boolean.FALSE)),
            "terminal child wait should be recovered and parent should continue");
        WorkflowNodeRun callChild = nodeRunCollection.find(Filters.and(
            Filters.eq("run_id", parentRun.id),
            Filters.eq("node_id", "call_child"))).stream().findFirst().orElseThrow();
        assertEquals(NodeRunStatus.COMPLETED, callChild.status);
        assertEquals("{\"q\":\"hello\"}", callChild.output);
    }
}
