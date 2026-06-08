package ai.core.server.workflow;

import ai.core.server.domain.RunStatus;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.engine.Frontier;
import ai.core.server.workflow.engine.NodeFactStatus;
import ai.core.server.workflow.engine.Planner;
import ai.core.server.workflow.engine.RunState;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;

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
    private static final String ROOT_SCOPE_KEY = "";

    private WorkflowAdvancer() {
    }

    public static RunStatus drive(WorkflowGraph graph, WorkflowRun run, WorkflowJournal journal,
                                  NodeExecutor executor, Executor pool, BooleanSupplier cancelled) {
        var inflight = new ConcurrentHashMap<String, CompletableFuture<Void>>();
        try {
            while (true) {
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
                    dispatch(graph, run, node, journal, executor, pool, inflight);
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
                return classify(finalState, finalFrontier);
            }
        } finally {
            awaitAll(inflight);   // drain in-flight tasks on cancel / exception / normal exit before returning
        }
    }

    private static void dispatch(WorkflowGraph graph, WorkflowRun run, WorkflowNode node, WorkflowJournal journal,
                                 NodeExecutor executor, Executor pool, Map<String, CompletableFuture<Void>> inflight) {
        var done = new CompletableFuture<Void>();
        inflight.put(node.id(), done);
        pool.execute(() -> {
            try {
                VariablePool vars = VariablePool.fromNodeRuns(journal.nodeRuns(run.id), ROOT_SCOPE_KEY, run.input);
                NodeContext ctx = new NodeContext(graph, run, node, List.of(), vars);
                NodeOutcome outcome = executor.execute(ctx);
                journal.recordOutcome(run, node, List.of(), outcome);
            } catch (RuntimeException e) {
                journal.recordOutcome(run, node, List.of(), new NodeOutcome.Fail(String.valueOf(e.getMessage()), false));
            } finally {
                // Order matters: remove from inflight BEFORE completing the future, so a drive thread woken by
                // awaitAny() re-reads an inflight map that no longer contains this node (else a transient spin).
                inflight.remove(node.id());
                done.complete(null);
            }
        });
    }

    // A retryable node failure currently terminalizes the run as FAILED; resumable manual retry (a PAUSED run
    // status) is a deferred feature — see design-workflow-engine.md section 12. A genuinely stuck run (no
    // failure, no output reached) also collapses to FAILED, intentionally.
    private static RunStatus classify(RunState state, Frontier frontier) {
        boolean anyFailed = state.facts().values().stream().anyMatch(fact -> fact.status() == NodeFactStatus.FAILED);
        if (anyFailed) {
            return RunStatus.FAILED;
        }
        return frontier.outputReached() ? RunStatus.COMPLETED : RunStatus.FAILED;
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
