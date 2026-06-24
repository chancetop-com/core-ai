package ai.core.server.workflow;

import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.engine.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MongoWorkflowJournalSuspendedTest {
    @Test
    void suspendedOutcomeParksNodeAsWaitingWithChildLink() {
        var journal = new InMemoryWorkflowJournal();
        var run = run();
        var node = new WorkflowNode("call_sub", "WORKFLOW");
        journal.appendRunning(run, node, List.of());

        journal.recordOutcome(run, node, List.of(), new NodeOutcome.Suspended("wfrun-child-1"));

        assertEquals(NodeRunStatus.WAITING, journal.status(run.id, "call_sub"));
        assertEquals("wfrun-child-1", journal.childRunId(run.id, "call_sub"));
    }

    // Local run factory mirroring AgentExecutorTest's private run().
    private static WorkflowRun run() {
        var run = new WorkflowRun();
        run.id = "run-1";
        run.workflowId = "wf-1";
        run.input = "{\"ticket\": \"login broken\"}";
        return run;
    }
}
