package ai.core.server.workflow;

import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.engine.WorkflowEdge;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowAdvancerTest {
    private static final Executor SAME_THREAD = Runnable::run;

    @Test
    void linearGraphDrivesToCompleted() {
        WorkflowGraph graph = graph(
            List.of(node("start"), node("a"), node("end")),
            List.of(edge("e0", "start", "a"), edge("e1", "a", "end")));
        var journal = new InMemoryWorkflowJournal();
        var executor = new ScriptedExecutor();

        RunStatus status = WorkflowAdvancer.drive(graph, run(), journal, executor, SAME_THREAD, () -> false);

        assertEquals(RunStatus.COMPLETED, status);
        assertEquals(List.of("start", "a", "end"), executor.executed);
        assertEquals(NodeRunStatus.COMPLETED, journal.status("run-1", "end"));
    }

    @Test
    void ifElseSkipsDeadArmAndCompletes() {
        WorkflowGraph graph = graph(
            List.of(node("start"), node("if"), node("left"), node("right"), node("merge"), node("end")),
            List.of(edge("e0", "start", "if"),
                edge("eL", "if", "left"), edge("eR", "if", "right"),
                edge("mL", "left", "merge"), edge("mR", "right", "merge"),
                edge("eEnd", "merge", "end")));
        var journal = new InMemoryWorkflowJournal();
        var executor = new ScriptedExecutor();
        executor.branch("if", "eL");

        RunStatus status = WorkflowAdvancer.drive(graph, run(), journal, executor, SAME_THREAD, () -> false);

        assertEquals(RunStatus.COMPLETED, status);
        assertTrue(executor.executed.contains("left"));
        assertFalse(executor.executed.contains("right"));
        assertEquals(NodeRunStatus.SKIPPED, journal.status("run-1", "right"));
        assertEquals(NodeRunStatus.COMPLETED, journal.status("run-1", "merge"));
    }

    @Test
    void failingNodeYieldsFailedRunAndHaltsDownstream() {
        WorkflowGraph graph = graph(
            List.of(node("start"), node("a"), node("b"), node("end")),
            List.of(edge("e0", "start", "a"), edge("e1", "a", "b"), edge("e2", "b", "end")));
        var journal = new InMemoryWorkflowJournal();
        var executor = new ScriptedExecutor();
        executor.fail("a");

        RunStatus status = WorkflowAdvancer.drive(graph, run(), journal, executor, SAME_THREAD, () -> false);

        assertEquals(RunStatus.FAILED, status);   // provisional: a retryable failure terminalizes the run (retry is P5)
        assertEquals(NodeRunStatus.FAILED_RETRYABLE, journal.status("run-1", "a"));
        assertFalse(executor.executed.contains("b"));
    }

    @Test
    void recoveryReusesCompletedNodeRunsWithoutReExecuting() {
        WorkflowGraph graph = graph(
            List.of(node("start"), node("a"), node("end")),
            List.of(edge("e0", "start", "a"), edge("e1", "a", "end")));
        var journal = new InMemoryWorkflowJournal();
        journal.seedCompleted("run-1", "start");
        journal.seedCompleted("run-1", "a");
        var executor = new ScriptedExecutor();

        RunStatus status = WorkflowAdvancer.drive(graph, run(), journal, executor, SAME_THREAD, () -> false);

        assertEquals(RunStatus.COMPLETED, status);
        assertEquals(List.of("end"), executor.executed);   // start/a reused from the journal, only end ran
    }

    @Test
    void stuckRunWithNoOutputReachedIsFailed() {
        // start branches choosing no real edge -> its only out-edge skips -> the sink is never reached
        WorkflowGraph graph = graph(
            List.of(node("start"), node("only"), node("end")),
            List.of(edge("eOnly", "start", "only"), edge("eEnd", "only", "end")));
        var journal = new InMemoryWorkflowJournal();
        var executor = new ScriptedExecutor();
        executor.branch("start", "noSuchEdge");

        RunStatus status = WorkflowAdvancer.drive(graph, run(), journal, executor, SAME_THREAD, () -> false);

        assertEquals(RunStatus.FAILED, status);
        assertEquals(NodeRunStatus.SKIPPED, journal.status("run-1", "end"));
    }

    // ---- concurrency: a real thread pool exercises the awaitAny/awaitAll blocking paths ----

    @Test
    void parallelFanOutOnRealPoolCompletes() {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            WorkflowGraph graph = graph(
                List.of(node("start"), node("a"), node("b"), node("c"), node("end")),
                List.of(edge("e1", "start", "a"), edge("e2", "start", "b"), edge("e3", "start", "c"),
                    edge("ea", "a", "end"), edge("eb", "b", "end"), edge("ec", "c", "end")));
            var journal = new InMemoryWorkflowJournal();
            var executor = new ScriptedExecutor();

            RunStatus status = WorkflowAdvancer.drive(graph, run(), journal, executor, pool, () -> false);

            assertEquals(RunStatus.COMPLETED, status);   // join over 3 concurrent branches; awaitAny actually parks
            assertTrue(executor.executed.containsAll(List.of("a", "b", "c", "end")));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failingBranchYieldsFailedOnRealPool() {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            WorkflowGraph graph = graph(
                List.of(node("start"), node("ok"), node("bad"), node("end")),
                List.of(edge("e1", "start", "ok"), edge("e2", "start", "bad"),
                    edge("e3", "ok", "end"), edge("e4", "bad", "end")));
            var journal = new InMemoryWorkflowJournal();
            var executor = new ScriptedExecutor();
            executor.fail("bad");

            RunStatus status = WorkflowAdvancer.drive(graph, run(), journal, executor, pool, () -> false);

            assertEquals(RunStatus.FAILED, status);
            assertEquals(NodeRunStatus.FAILED_RETRYABLE, journal.status("run-1", "bad"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void midDriveCancelReturnsCancelledAfterDraining() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancel = new AtomicBoolean(false);
        try {
            WorkflowGraph graph = graph(
                List.of(node("start"), node("slow"), node("end")),
                List.of(edge("e0", "start", "slow"), edge("e1", "slow", "end")));
            var journal = new InMemoryWorkflowJournal();
            NodeExecutor executor = ctx -> {
                if (ctx.node().id().equals("slow")) {
                    started.countDown();
                    await(release);   // park the node so the drive thread parks in awaitAny
                }
                return new NodeOutcome.Normal("{}");
            };

            CompletableFuture<RunStatus> driving = CompletableFuture.supplyAsync(
                () -> WorkflowAdvancer.drive(graph, run(), journal, executor, pool, cancel::get));
            assertTrue(started.await(2, TimeUnit.SECONDS));   // slow node is in flight, drive parked in awaitAny
            cancel.set(true);                                  // cancel arrives mid-drive
            release.countDown();                               // let the in-flight node finish

            assertEquals(RunStatus.CANCELLED, driving.get(3, TimeUnit.SECONDS));
            assertEquals(NodeRunStatus.COMPLETED, journal.status("run-1", "slow"));   // awaitAll drained the in-flight node
            assertNull(journal.status("run-1", "end"));                               // cancel returned before planning end
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- helpers ----

    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static WorkflowRun run() {
        var run = new WorkflowRun();
        run.id = "run-1";
        run.workflowId = "wf-1";
        return run;
    }

    private static WorkflowGraph graph(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        return new WorkflowGraph(nodes, edges);
    }

    private static WorkflowNode node(String id) {
        return new WorkflowNode(id, id);
    }

    private static WorkflowEdge edge(String id, String source, String target) {
        return new WorkflowEdge(id, source, target);
    }

    static final class ScriptedExecutor implements NodeExecutor {
        final List<String> executed = new CopyOnWriteArrayList<>();
        private final Map<String, NodeOutcome> scripted = new HashMap<>();

        void branch(String nodeId, String chosenEdgeId) {
            scripted.put(nodeId, new NodeOutcome.Branch("{}", List.of(chosenEdgeId)));
        }

        void fail(String nodeId) {
            scripted.put(nodeId, new NodeOutcome.Fail("boom", false));
        }

        @Override
        public NodeOutcome execute(NodeContext ctx) {
            executed.add(ctx.node().id());
            return scripted.getOrDefault(ctx.node().id(), new NodeOutcome.Normal("{}"));
        }
    }
}
