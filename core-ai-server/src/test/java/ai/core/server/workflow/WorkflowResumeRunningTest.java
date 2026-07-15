package ai.core.server.workflow;

import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TriggerType;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowNodeRun;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live Mongo coverage for the concurrent-approve fix: a HUMAN_INPUT node can be resumed while the run is still
 * RUNNING (a parallel branch keeps it RUNNING past the node-level WAITING), and a PAUSED run stranded by the
 * settle/park race is recovered by the runner job.
 *
 * @author Xander
 */
@EnabledIf("mongoReachable")
@ExtendWith(IntegrationExtension.class)
@Context(module = WorkflowTestModule.class)
class WorkflowResumeRunningTest {
    private static final String GRAPH = """
        {"nodes": [{"id": "start", "type": "START"},
                   {"id": "human", "type": "HUMAN_INPUT", "config": {"mode": "input"}},
                   {"id": "end", "type": "END"}],
         "edges": [{"id": "e0", "source": "start", "target": "human"},
                   {"id": "e1", "source": "human", "target": "end"}]}
        """;

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
    void resumeAcceptsRunningRunAndDrivesToCompletion() {
        String runId = pauseOnHumanNode("resume-running");

        // a parallel branch would keep the run RUNNING even though the human node is already WAITING; simulate that
        // and let the lease expire so the next advance() can re-claim
        runCollection.update(
            Filters.eq("_id", runId),
            Updates.combine(Updates.set("status", RunStatus.RUNNING), Updates.set("lease_until", ZonedDateTime.now().minusSeconds(1))));

        runService.resume(runId, "human", null, "{}", "user-1");   // must NOT 409 on a RUNNING run
        assertEquals(NodeRunStatus.COMPLETED, nodeStatus(runId, "human"));

        runner.advance(runId);   // re-claim and drive the now-ready downstream
        assertEquals(RunStatus.COMPLETED, runCollection.get(runId).orElseThrow().status);
    }

    @Test
    void jobRecoversStrandedPausedRunWithNoWaitingNode() {
        String runId = pauseOnHumanNode("stranded-paused");

        // the settle/park race: the human node is already settled (COMPLETED) but the run stayed PAUSED with its
        // lease expired and no driver — exactly the stranded state the runner job must recover
        nodeRunCollection.update(
            Filters.and(Filters.eq("run_id", runId), Filters.eq("node_id", "human")),
            Updates.combine(Updates.set("status", NodeRunStatus.COMPLETED), Updates.set("output", "{}")));
        runCollection.update(
            Filters.eq("_id", runId),
            Updates.set("lease_until", ZonedDateTime.now().minusSeconds(1)));

        runnerJob.execute(null);   // claim sweep + stranded-PAUSED recovery

        assertTrue(awaitUntil(() -> runCollection.get(runId).map(r -> r.status == RunStatus.COMPLETED).orElse(Boolean.FALSE)),
            "stranded PAUSED run should be recovered and driven to COMPLETED");
    }

    private String pauseOnHumanNode(String name) {
        WorkflowDefinition definition = definitionService.create(name, "WORKFLOW", GRAPH, "user-1");
        publishService.publish(definition.id, "user-1");
        WorkflowRun created = runService.createRun(definition.id, "{}", TriggerType.API, "user-1");
        runner.advance(created.id);
        assertEquals(RunStatus.PAUSED, runCollection.get(created.id).orElseThrow().status);
        assertEquals(NodeRunStatus.WAITING, nodeStatus(created.id, "human"));
        return created.id;
    }

    private NodeRunStatus nodeStatus(String runId, String nodeId) {
        return nodeRunCollection.find(Filters.and(Filters.eq("run_id", runId), Filters.eq("node_id", nodeId)))
            .stream().findFirst().orElseThrow().status;
    }
}
