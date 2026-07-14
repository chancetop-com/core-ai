package ai.core.server.workflow;

import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.engine.WorkflowEdge;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;
import ai.core.server.workflow.executor.StartExecutor;
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

    // ---- helpers ----

    private static boolean awaitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
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

    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitLong(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static WorkflowRun run() {
        return run(null);
    }

    private static WorkflowRun run(String input) {
        var run = new WorkflowRun();
        run.id = "run-1";
        run.workflowId = "wf-1";
        run.input = input;
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

    @Test
    void linearGraphDrivesToCompleted() {
        WorkflowGraph graph = graph(
            List.of(node("start"), node("a"), node("end")),
            List.of(edge("e0", "start", "a"), edge("e1", "a", "end")));
        var journal = new InMemoryWorkflowJournal();
        var executor = new ScriptedExecutor();

        RunStatus status = WorkflowAdvancer.drive(graph, run(), journal, new WorkflowAdvancer.ExecCtx(executor, SAME_THREAD), () -> false);

        assertEquals(RunStatus.COMPLETED, status);
        assertEquals(List.of("start", "a", "end"), executor.executed);
        assertEquals(NodeRunStatus.COMPLETED, journal.status("run-1", "end"));
    }

    @Test
    void recordsResolvedInputSnapshotForTrace() {
        WorkflowNode agent = new WorkflowNode("agent", "AGENT", List.of(),
            Map.of("input", "Hello {{ nodes.start.output.name }}"));
        WorkflowGraph graph = graph(
            List.of(new WorkflowNode("start", "START"), agent, new WorkflowNode("end", "END")),
            List.of(edge("e0", "start", "agent"), edge("e1", "agent", "end")));
        var journal = new InMemoryWorkflowJournal();
        NodeExecutor executor = ctx -> "START".equals(ctx.node().type())
            ? new StartExecutor().execute(ctx)
            : new NodeOutcome.Normal("{}");

        RunStatus status = WorkflowAdvancer.drive(
            graph, run("{\"name\":\"Ada\"}"), journal, new WorkflowAdvancer.ExecCtx(executor, SAME_THREAD), () -> false);

        assertEquals(RunStatus.COMPLETED, status);
        assertEquals("{\"name\":\"Ada\"}", journal.inputJson("run-1", "start"));
        assertTrue(journal.inputJson("run-1", "agent").contains("\"input\":\"Hello Ada\""));
    }

    @Test
    void redactsSecretLikeHttpHeadersInInputSnapshot() {
        WorkflowNode http = new WorkflowNode("http", "HTTP", List.of(), Map.of(
            "url", "https://api.example.com",
            "headers", Map.of(
                "ApiKey", "key-1",
                "Access-Token", "token-1",
                "Private-Key", "private-1",
                "Accept", "application/json")));
        WorkflowGraph graph = graph(
            List.of(new WorkflowNode("start", "START"), http, new WorkflowNode("end", "END")),
            List.of(edge("e0", "start", "http"), edge("e1", "http", "end")));
        var journal = new InMemoryWorkflowJournal();
        NodeExecutor executor = ctx -> "START".equals(ctx.node().type())
            ? new StartExecutor().execute(ctx)
            : new NodeOutcome.Normal("{}");

        RunStatus status = WorkflowAdvancer.drive(graph, run(), journal, new WorkflowAdvancer.ExecCtx(executor, SAME_THREAD), () -> false);

        assertEquals(RunStatus.COMPLETED, status);
        String snapshot = journal.inputJson("run-1", "http");
        assertFalse(snapshot.contains("key-1"));
        assertFalse(snapshot.contains("token-1"));
        assertFalse(snapshot.contains("private-1"));
        assertTrue(snapshot.contains("application/json"));
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

        RunStatus status = WorkflowAdvancer.drive(graph, run(), journal, new WorkflowAdvancer.ExecCtx(executor, SAME_THREAD), () -> false);

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

        RunStatus status = WorkflowAdvancer.drive(graph, run(), journal, new WorkflowAdvancer.ExecCtx(executor, SAME_THREAD), () -> false);

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

        RunStatus status = WorkflowAdvancer.drive(graph, run(), journal, new WorkflowAdvancer.ExecCtx(executor, SAME_THREAD), () -> false);

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

        RunStatus status = WorkflowAdvancer.drive(graph, run(), journal, new WorkflowAdvancer.ExecCtx(executor, SAME_THREAD), () -> false);

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

            RunStatus status = WorkflowAdvancer.drive(graph, run(), journal, new WorkflowAdvancer.ExecCtx(executor, pool), () -> false);

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

            RunStatus status = WorkflowAdvancer.drive(graph, run(), journal, new WorkflowAdvancer.ExecCtx(executor, pool), () -> false);

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
                () -> WorkflowAdvancer.drive(graph, run(), journal, new WorkflowAdvancer.ExecCtx(executor, pool), cancel::get));
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

    @Test
    void executorThrowingErrorIsRecordedAsFailNotStuckRunning() {
        WorkflowGraph graph = graph(
            List.of(node("start"), node("a"), node("b"), node("end")),
            List.of(edge("e0", "start", "a"), edge("e1", "a", "b"), edge("e2", "b", "end")));
        var journal = new InMemoryWorkflowJournal();
        NodeExecutor executor = ctx -> {
            if (ctx.node().id().equals("a")) {
                throw new StackOverflowError("boom");   // an Error, not a RuntimeException (e.g. runaway user code)
            }
            return new NodeOutcome.Normal("{}");
        };

        RunStatus status = WorkflowAdvancer.drive(graph, run(), journal, new WorkflowAdvancer.ExecCtx(executor, SAME_THREAD), () -> false);

        assertEquals(RunStatus.FAILED, status);   // drive returns instead of leaving 'a' stuck RUNNING forever
        assertEquals(NodeRunStatus.FAILED_RETRYABLE, journal.status("run-1", "a"));
        assertNull(journal.status("run-1", "b"));
    }

    @Test
    void lostLeaseStopsDriveAndSuppressesCommit() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var held = new AtomicBoolean(true);
        try {
            WorkflowGraph graph = graph(
                List.of(node("start"), node("slow"), node("end")),
                List.of(edge("e0", "start", "slow"), edge("e1", "slow", "end")));
            var journal = new InMemoryWorkflowJournal();
            NodeExecutor executor = ctx -> {
                if (ctx.node().id().equals("slow")) {
                    started.countDown();
                    await(release);
                }
                return new NodeOutcome.Normal("{}");
            };

            CompletableFuture<RunStatus> driving = CompletableFuture.supplyAsync(
                () -> WorkflowAdvancer.drive(graph, run(), journal, new WorkflowAdvancer.ExecCtx(executor, pool), () -> false, held::get));
            assertTrue(started.await(2, TimeUnit.SECONDS));   // slow node in flight, drive parked in awaitAny
            held.set(false);                                   // lease lost to another replica
            release.countDown();                               // let the in-flight node finish

            assertEquals(RunStatus.RUNNING, driving.get(3, TimeUnit.SECONDS));      // handoff sentinel, not finalized by us
            assertEquals(NodeRunStatus.RUNNING, journal.status("run-1", "slow"));   // commit suppressed -> not clobbered
            assertNull(journal.status("run-1", "end"));                             // no new dispatch after lease loss
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void humanInputNodePausesRunThenResumesToCompletion() {
        WorkflowGraph graph = graph(
            List.of(node("start"), node("ask"), node("end")),
            List.of(edge("e0", "start", "ask"), edge("e1", "ask", "end")));
        var journal = new InMemoryWorkflowJournal();
        NodeExecutor executor = ctx -> ctx.node().id().equals("ask")
            ? new NodeOutcome.Waiting("{\"mode\":\"input\"}")
            : new NodeOutcome.Normal("{}");

        // first drive parks the run on the human-input node — out-edges stay PENDING, downstream not reached
        RunStatus paused = WorkflowAdvancer.drive(graph, run(), journal, new WorkflowAdvancer.ExecCtx(executor, SAME_THREAD), () -> false);
        assertEquals(RunStatus.PAUSED, paused);
        assertEquals(NodeRunStatus.WAITING, journal.status("run-1", "ask"));
        assertNull(journal.status("run-1", "end"));

        // the resume endpoint settles the waiting node to COMPLETED; re-driving (re-fold) continues to the end
        journal.seedCompleted("run-1", "ask");
        RunStatus completed = WorkflowAdvancer.drive(graph, run(), journal, new WorkflowAdvancer.ExecCtx(executor, SAME_THREAD), () -> false);
        assertEquals(RunStatus.COMPLETED, completed);
        assertEquals(NodeRunStatus.COMPLETED, journal.status("run-1", "end"));
    }

    @Test
    void siblingFailureWhileHumanWaitingPausesNotFails() {
        // start fans out to a failing branch and a human-input branch, both feeding a join END. A sibling failure
        // must NOT finalize the run FAILED while the human node is still WAITING — the run must stay resumable.
        WorkflowGraph graph = graph(
            List.of(node("start"), node("bad"), node("ask"), node("end")),
            List.of(edge("e0", "start", "bad"), edge("e1", "start", "ask"),
                edge("e2", "bad", "end"), edge("e3", "ask", "end")));
        var journal = new InMemoryWorkflowJournal();
        NodeExecutor executor = ctx -> switch (ctx.node().id()) {
            case "bad" -> new NodeOutcome.Fail("boom", false);
            case "ask" -> new NodeOutcome.Waiting("{\"mode\":\"approval\"}");
            default -> new NodeOutcome.Normal("{}");
        };

        RunStatus status = WorkflowAdvancer.drive(graph, run(), journal, new WorkflowAdvancer.ExecCtx(executor, SAME_THREAD), () -> false);

        assertEquals(RunStatus.PAUSED, status);   // a still-WAITING human node keeps the run resumable past a sibling failure
        assertEquals(NodeRunStatus.WAITING, journal.status("run-1", "ask"));
        assertEquals(NodeRunStatus.FAILED_RETRYABLE, journal.status("run-1", "bad"));
    }

    @Test
    void humanResumeWhileSiblingRunningDrivesApprovedBranchConcurrently() throws Exception {
        // start fans out to a slow agent (blocked) and a human-input node. While the slow agent is still RUNNING,
        // the approval is settled out-of-band (as the resume endpoint does). The driver must re-fold and drive the
        // approved branch CONCURRENTLY — without waiting for the slow sibling to finish.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        var slowStarted = new CountDownLatch(1);
        var slowRelease = new CountDownLatch(1);
        try {
            WorkflowGraph graph = graph(
                List.of(node("start"), node("slow"), node("ask"), node("approved"), node("end")),
                List.of(edge("e0", "start", "slow"), edge("e1", "start", "ask"),
                    edge("eApprove", "ask", "approved"), edge("e2", "slow", "end"), edge("e3", "approved", "end")));
            var journal = new InMemoryWorkflowJournal();
            NodeExecutor executor = ctx -> {
                switch (ctx.node().id()) {
                    case "slow" -> {
                        slowStarted.countDown();
                        awaitLong(slowRelease);   // hold the sibling in flight far longer than the approved-branch window
                        return new NodeOutcome.Normal("{}");
                    }
                    case "ask" -> {
                        return new NodeOutcome.Waiting("{\"mode\":\"approval\"}");
                    }
                    default -> {
                        return new NodeOutcome.Normal("{}");
                    }
                }
            };

            CompletableFuture<RunStatus> driving = CompletableFuture.supplyAsync(
                () -> WorkflowAdvancer.drive(graph, run(), journal, new WorkflowAdvancer.ExecCtx(executor, pool), () -> false));

            assertTrue(slowStarted.await(2, TimeUnit.SECONDS));                 // slow sibling is in flight
            assertTrue(awaitUntil(() -> journal.status("run-1", "ask") == NodeRunStatus.WAITING));   // human node parked
            Thread.sleep(400);   // let the driver settle into awaitAny({slow}); now only a poll can re-fold it

            journal.seedCompleted("run-1", "ask");   // the resume endpoint settles the WAITING node mid-run

            // the approved branch must run while the slow sibling is STILL blocked — proves concurrent re-fold
            assertTrue(awaitUntil(() -> journal.status("run-1", "approved") == NodeRunStatus.COMPLETED),
                "approved branch should drive while the slow sibling is still running");

            slowRelease.countDown();
            assertEquals(RunStatus.COMPLETED, driving.get(5, TimeUnit.SECONDS));
            assertEquals(NodeRunStatus.COMPLETED, journal.status("run-1", "end"));
        } finally {
            slowRelease.countDown();
            pool.shutdownNow();
        }
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
