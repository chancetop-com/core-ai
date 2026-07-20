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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    // While a node is parked WAITING on human input AND sibling branches are still in flight, a human resume settling
    // that node is an out-of-band journal write the awaitAny(inflight) block would otherwise miss until a sibling
    // finishes. Bounding the wait makes the driver re-fold periodically so the approved branch runs concurrently.
    private static final long PARKED_HUMAN_POLL_MILLIS = 500;

    public static RunStatus drive(WorkflowGraph graph, WorkflowRun run, WorkflowJournal journal,
                                  ExecCtx exec, BooleanSupplier cancelled) {
        return drive(graph, run, journal, exec, cancelled, () -> true);
    }

    /**
     * @param leaseHeld returns false once this worker has lost the run-level lease (a slow/dead heartbeat let
     *                  another replica claim the run). When that happens the loop stops dispatching new nodes,
     *                  in-flight commits are suppressed (the new owner owns the journal), and drive returns
     *                  {@link RunStatus#RUNNING} as a "handed off, not finalized by me" sentinel — the runner
     *                  checks the same flag and skips finalization so it can't overwrite the new owner.
     */
    public static RunStatus drive(WorkflowGraph graph, WorkflowRun run, WorkflowJournal journal,
                                  ExecCtx exec, BooleanSupplier cancelled, BooleanSupplier leaseHeld) {
        NodeExecutor executor = exec.executor();
        Executor pool = exec.pool();
        var inflight = new ConcurrentHashMap<String, CompletableFuture<Void>>();
        try {
            while (true) {
                if (!leaseHeld.getAsBoolean()) {
                    return RunStatus.RUNNING;   // lost the lease -> hand off; the runner will not finalize
                }
                if (cancelled.getAsBoolean()) {
                    return RunStatus.CANCELLED;
                }
                List<WorkflowNodeRun> nodeRuns = journal.nodeRuns(run.id);
                RunState state = RunStateAssembler.toRunState(nodeRuns, ROOT_SCOPE_KEY);
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
                    dispatch(new DispatchCtx(graph, run, node, journal, executor, pool, inflight, leaseHeld));
                }
                if (progressed) {
                    continue;   // re-plan immediately on any new ready/skip
                }
                if (!inflight.isEmpty()) {
                    // a parked human node + in-flight siblings -> bound the wait so an out-of-band resume is re-folded
                    // promptly (concurrent approve); otherwise block until an in-flight node completes, then re-plan
                    if (hasWaitingNode(nodeRuns)) {
                        awaitAny(inflight, PARKED_HUMAN_POLL_MILLIS);
                    } else {
                        awaitAny(inflight);
                    }
                    continue;
                }
                RunStatus finalStatus = finalizeRun(graph, run, journal);
                if (finalStatus != null) {
                    return finalStatus;
                }
            }
        } finally {
            awaitAll(inflight);   // drain in-flight tasks on cancel / exception / normal exit before returning
        }
    }

    // Returns null if replanning is needed (just-missed completion opened new work),
    // otherwise the final run status from classify().
    private static RunStatus finalizeRun(WorkflowGraph graph, WorkflowRun run, WorkflowJournal journal) {
        List<WorkflowNodeRun> finalRuns = journal.nodeRuns(run.id);
        RunState finalState = RunStateAssembler.toRunState(finalRuns, ROOT_SCOPE_KEY);
        Frontier finalFrontier = Planner.plan(graph, finalState);
        if (finalFrontier.hasProgress()) {
            return null;   // a just-missed completion opened new work
        }
        return classify(finalState, finalFrontier, hasWaitingNode(finalRuns));
    }

    private static void dispatch(DispatchCtx dctx) {
        WorkflowGraph graph = dctx.graph();
        WorkflowRun run = dctx.run();
        WorkflowNode node = dctx.node();
        WorkflowJournal journal = dctx.journal();
        NodeExecutor executor = dctx.executor();
        Executor pool = dctx.pool();
        Map<String, CompletableFuture<Void>> inflight = dctx.inflight();
        BooleanSupplier leaseHeld = dctx.leaseHeld();
        var done = new CompletableFuture<Void>();
        inflight.put(node.id(), done);
        pool.execute(() -> {
            try {
                VariablePool vars = VariablePool.fromNodeRuns(journal.nodeRuns(run.id), ROOT_SCOPE_KEY, run.input);
                NodeContext ctx = new NodeContext(graph, run, node, List.of(), vars);
                recordInput(journal, run, node, ctx);
                NodeOutcome outcome = executor.execute(ctx);
                commit(journal, run, node, outcome, leaseHeld);
            } catch (Throwable e) {
                // catch Throwable (not just RuntimeException): user code in a CODE node can throw Error
                // (StackOverflow/OOM); without this the outcome is never recorded and the node stays RUNNING forever.
                LOGGER.error("workflow node execution failed, runId={}, nodeId={}", run.id, node.id(), e);
                commit(journal, run, node, new NodeOutcome.Fail(errorMessage(e), StackTraceFormatter.format(e), false), leaseHeld);
            } finally {
                // Order matters: remove from inflight BEFORE completing the future, so a drive thread woken by
                // awaitAny() re-reads an inflight map that no longer contains this node (else a transient spin).
                inflight.remove(node.id());
                done.complete(null);
            }
        });
    }

    static String errorMessage(Throwable error) {
        return error.getMessage() != null ? error.getMessage() : error.toString();
    }

    private static void recordInput(WorkflowJournal journal, WorkflowRun run, WorkflowNode node, NodeContext ctx) {
        try {
            journal.recordInput(run, node, List.of(), NodeInputSnapshot.capture(ctx));
        } catch (RuntimeException e) {
            LOGGER.warn("failed to record workflow node input snapshot, runId={}, nodeId={}", run.id, node.id(), e);
        }
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

    // Terminal classification. A still-WAITING root-scope HUMAN_INPUT node wins first: the run must stay PAUSED and
    // resumable even if a sibling branch failed, so a parallel failure can never strand a pending human approval.
    // Then the original order: a real failure -> FAILED; else output reached -> COMPLETED; else genuinely stuck -> FAILED.
    private static RunStatus classify(RunState state, Frontier frontier, boolean hasWaiting) {
        if (hasWaiting) {
            return RunStatus.PAUSED;
        }
        boolean anyFailed = state.facts().values().stream().anyMatch(fact -> fact.status() == NodeFactStatus.FAILED);
        if (anyFailed) {
            return RunStatus.FAILED;
        }
        if (frontier.outputReached()) {
            return RunStatus.COMPLETED;
        }
        return RunStatus.FAILED;
    }

    // A WAITING node-run at the root scope means the run is parked on human input, not stuck. Derived from the same
    // node-run snapshot the caller classified from: WAITING projects to a RUNNING fact for edge purposes, so the
    // planner can't distinguish it, and a separate read could disagree with finalState under a concurrent settle.
    private static boolean hasWaitingNode(List<WorkflowNodeRun> nodeRuns) {
        for (WorkflowNodeRun nodeRun : nodeRuns) {
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

    // Bounded variant: wake on the first in-flight completion OR the poll deadline, so the loop re-folds and observes
    // out-of-band journal writes (a human resume settling a WAITING node, possibly on another replica) without
    // waiting for a still-running sibling. A node task never completes exceptionally (dispatch wraps every throwable
    // into a Fail commit), so the ExecutionException arm is defensive only.
    private static void awaitAny(Map<String, CompletableFuture<Void>> inflight, long timeoutMillis) {
        CompletableFuture<?>[] futures = inflight.values().toArray(CompletableFuture[]::new);
        if (futures.length == 0) {
            return;
        }
        try {
            CompletableFuture.anyOf(futures).get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
            // poll tick: fall through to re-plan
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LOGGER.warn("in-flight node future completed exceptionally during bounded wait", e);
        }
    }

    private static void awaitAll(Map<String, CompletableFuture<Void>> inflight) {
        CompletableFuture<?>[] futures = inflight.values().toArray(CompletableFuture[]::new);
        if (futures.length > 0) {
            CompletableFuture.allOf(futures).join();
        }
    }

    private WorkflowAdvancer() {
    }

    public record ExecCtx(NodeExecutor executor, Executor pool) {
    }

    private record DispatchCtx(WorkflowGraph graph, WorkflowRun run, WorkflowNode node, WorkflowJournal journal,
                                NodeExecutor executor, Executor pool, Map<String, CompletableFuture<Void>> inflight,
                                BooleanSupplier leaseHeld) {
    }
}
