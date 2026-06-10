package ai.core.server.workflow;

import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.engine.Frontier;
import ai.core.server.workflow.engine.NodeFactStatus;
import ai.core.server.workflow.engine.Planner;
import ai.core.server.workflow.engine.RunState;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * Drives one claimed run to completion: the spec's continuous ready-queue. Pure of Mongo and DI — its three
 * collaborators (journal, executor, pool) are injected — so the whole drive-to-completion behaviour is
 * unit-testable with fakes. Production wiring lives in {@link WorkflowRunner}.
 *
 * <p>Loop: plan, dispatch every ready node (off the pool) and append every skip, re-plan immediately on any
 * progress, otherwise block until an in-flight node completes. Completion = frontier EXHAUSTED and nothing in
 * flight; the final status is classified from the last frontier + facts. P0 drives the root scope only;
 * container scopes (P3) extend this with a scope key.
 *
 * @author Xander
 */
public final class WorkflowAdvancer {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowAdvancer.class);
    private static final String ROOT_SCOPE_KEY = "";
    private static final int COMMIT_RETRIES = 3;

    private WorkflowAdvancer() {
    }

    public static RunStatus drive(WorkflowGraph graph, WorkflowRun run, WorkflowJournal journal,
                                  NodeExecutor executor, Executor pool, BooleanSupplier cancelled) {
        return drive(graph, run, journal, executor, pool, cancelled, () -> true);
    }

    /**
     * @param leaseHeld returns false once this worker has lost the run-level lease (a slow/dead heartbeat let
     *                  another replica claim the run). When that happens the loop stops dispatching new nodes,
     *                  in-flight commits are suppressed (the new owner owns the journal), and drive returns
     *                  {@link RunStatus#RUNNING} as a "handed off, not finalized by me" sentinel — the runner
     *                  checks the same flag and skips finalization so it can't overwrite the new owner.
     */
    public static RunStatus drive(WorkflowGraph graph, WorkflowRun run, WorkflowJournal journal,
                                  NodeExecutor executor, Executor pool, BooleanSupplier cancelled, BooleanSupplier leaseHeld) {
        var inflight = new ConcurrentHashMap<String, CompletableFuture<Void>>();
        try {
            while (true) {
                if (!leaseHeld.getAsBoolean()) {
                    return RunStatus.RUNNING;   // lost the lease -> hand off; the runner will not finalize
                }
                if (cancelled.getAsBoolean()) {
                    return RunStatus.CANCELLED;
                }
                RunState state = RunStateAssembler.toRunState(journal.nodeRuns(run.id), ROOT_SCOPE_KEY);
                Frontier frontier = Planner.plan(graph, state);

                boolean progressed = false;
                for (String skipId : frontier.skipNodeIds()) {
                    journal.appendSkipped(run, graph.node(skipId), List.of());
                    progressed = true;
                }
                for (String readyId : frontier.readyNodeIds()) {
                    WorkflowNode node = graph.node(readyId);
                    if (!journal.appendRunning(run, node, List.of())) {
                        continue;   // a concurrent dispatch already owns it (unique index)
                    }
                    progressed = true;
                    dispatch(graph, run, node, journal, executor, pool, inflight, leaseHeld);
                }
                if (progressed) {
                    continue;   // re-plan immediately on any new ready/skip
                }
                if (!inflight.isEmpty()) {
                    awaitAny(inflight);   // block until an in-flight node completes, then re-plan
                    continue;
                }
                // inflight is empty -> the journal is now stable (recordOutcome happens-before each inflight
                // remove). Re-plan from a fresh read: a node may have completed between the plan above and this
                // check, so classifying from the stale frontier could wrongly report FAILED.
                RunState finalState = RunStateAssembler.toRunState(journal.nodeRuns(run.id), ROOT_SCOPE_KEY);
                Frontier finalFrontier = Planner.plan(graph, finalState);
                if (finalFrontier.hasProgress()) {
                    continue;   // a just-missed completion opened new work
                }
                return classify(finalState, finalFrontier, hasWaitingNode(journal, run));
            }
        } finally {
            awaitAll(inflight);   // drain in-flight tasks on cancel / exception / normal exit before returning
        }
    }

    private static void dispatch(WorkflowGraph graph, WorkflowRun run, WorkflowNode node, WorkflowJournal journal,
                                 NodeExecutor executor, Executor pool, Map<String, CompletableFuture<Void>> inflight,
                                 BooleanSupplier leaseHeld) {
        var done = new CompletableFuture<Void>();
        inflight.put(node.id(), done);
        pool.execute(() -> {
            try {
                VariablePool vars = VariablePool.fromNodeRuns(journal.nodeRuns(run.id), ROOT_SCOPE_KEY, run.input);
                NodeContext ctx = new NodeContext(graph, run, node, List.of(), vars);
                NodeOutcome outcome = executor.execute(ctx);
                commit(journal, run, node, outcome, leaseHeld);
            } catch (Throwable e) {
                // catch Throwable (not just RuntimeException): user code in a CODE node can throw Error
                // (StackOverflow/OOM); without this the outcome is never recorded and the node stays RUNNING forever.
                commit(journal, run, node, new NodeOutcome.Fail(String.valueOf(e.getMessage()), false), leaseHeld);
            } finally {
                // Order matters: remove from inflight BEFORE completing the future, so a drive thread woken by
                // awaitAny() re-reads an inflight map that no longer contains this node (else a transient spin).
                inflight.remove(node.id());
                done.complete(null);
            }
        });
    }

    // Record the outcome, but only while this worker still holds the lease — once the lease is lost the new owner
    // has reset our orphaned RUNNING node-run, so a late write here would clobber its view. A transient Mongo
    // failure on the write is retried a few times so a successful node's COMPLETED result isn't silently lost.
    private static void commit(WorkflowJournal journal, WorkflowRun run, WorkflowNode node, NodeOutcome outcome,
                               BooleanSupplier leaseHeld) {
        if (!leaseHeld.getAsBoolean()) {
            return;
        }
        RuntimeException last = null;
        for (int attempt = 0; attempt < COMMIT_RETRIES; attempt++) {
            try {
                journal.recordOutcome(run, node, List.of(), outcome);
                return;
            } catch (RuntimeException e) {
                last = e;
                sleepQuietly(50L * (attempt + 1));
            }
        }
        LOGGER.error("failed to record node outcome after {} attempts, runId={}, nodeId={}", COMMIT_RETRIES, run.id, node.id(), last);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Terminal classification. Order: a real failure -> FAILED; else output reached -> COMPLETED; else parked on a
    // HUMAN_INPUT node -> PAUSED (the run resumes when the human responds); else genuinely stuck -> FAILED.
    private static RunStatus classify(RunState state, Frontier frontier, boolean hasWaiting) {
        boolean anyFailed = state.facts().values().stream().anyMatch(fact -> fact.status() == NodeFactStatus.FAILED);
        if (anyFailed) {
            return RunStatus.FAILED;
        }
        if (frontier.outputReached()) {
            return RunStatus.COMPLETED;
        }
        return hasWaiting ? RunStatus.PAUSED : RunStatus.FAILED;
    }

    // A WAITING node-run at the root scope means the run is parked on human input, not stuck. Read straight from
    // the journal: WAITING projects to a RUNNING fact for edge purposes, so the planner can't distinguish it.
    private static boolean hasWaitingNode(WorkflowJournal journal, WorkflowRun run) {
        for (WorkflowNodeRun nodeRun : journal.nodeRuns(run.id)) {
            if (nodeRun.status == NodeRunStatus.WAITING && ROOT_SCOPE_KEY.equals(nodeRun.scopePathKey)) {
                return true;
            }
        }
        return false;
    }

    private static void awaitAny(Map<String, CompletableFuture<Void>> inflight) {
        CompletableFuture<?>[] futures = inflight.values().toArray(CompletableFuture[]::new);
        if (futures.length > 0) {
            CompletableFuture.anyOf(futures).join();
        }
    }

    private static void awaitAll(Map<String, CompletableFuture<Void>> inflight) {
        CompletableFuture<?>[] futures = inflight.values().toArray(CompletableFuture[]::new);
        if (futures.length > 0) {
            CompletableFuture.allOf(futures).join();
        }
    }
}
