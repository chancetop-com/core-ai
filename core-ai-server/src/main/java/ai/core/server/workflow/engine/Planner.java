package ai.core.server.workflow.engine;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The engine's only control logic: a pure, total, deterministic fold from (graph, durable facts) to the
 * next frontier. It mutates nothing and depends on no infrastructure, so it is unit-testable with zero
 * mocks, and recovery is literally "call {@code plan} again over the persisted facts".
 *
 * <p>Edge verdicts are derived, never stored. Readiness and skip are two halves of one predicate, which
 * makes parallel fan-out, branch, join and skip-propagation a single deadlock-free mechanism (the lattice
 * is monotone: an edge moves PENDING to ACTIVE/SKIPPED exactly once, never back).
 *
 * @author Xander
 */
public final class Planner {
    private Planner() {
    }

    public static Frontier plan(WorkflowGraph graph, RunState state) {
        Map<String, EdgeVerdict> verdicts = new LinkedHashMap<>();
        for (WorkflowEdge edge : graph.edges()) {
            verdicts.put(edge.id(), deriveVerdict(state.factOf(edge.source()), edge));
        }

        Set<String> ready = new LinkedHashSet<>();
        Set<String> skip = new LinkedHashSet<>();
        boolean outputReached = false;

        for (WorkflowNode node : graph.nodes()) {
            NodeFact fact = state.factOf(node.id());
            if (fact != null) {
                // A node with a node-run is never re-dispatched. A completed END (no out-edges) is recorded as
                // an output reached: a success signal, NOT a stop condition (the run ends on frontier exhaustion).
                if (fact.status() == NodeFactStatus.COMPLETED && graph.outEdges(node.id()).isEmpty()) {
                    outputReached = true;
                }
                continue;
            }
            List<WorkflowEdge> ins = graph.inEdges(node.id());
            if (ins.isEmpty()) {
                ready.add(node.id());   // START: no in-edges, seeded ready
                continue;
            }
            boolean allTerminal = true;
            boolean anyActive = false;
            for (WorkflowEdge edge : ins) {
                EdgeVerdict verdict = verdicts.get(edge.id());
                if (verdict == EdgeVerdict.PENDING) {
                    allTerminal = false;
                    break;
                }
                if (verdict == EdgeVerdict.ACTIVE) {
                    anyActive = true;
                }
            }
            if (!allTerminal) {
                continue;
            }
            if (anyActive) {
                ready.add(node.id());   // join fires on >=1 ACTIVE in-edge
            } else {
                skip.add(node.id());    // all in-edges terminal and none ACTIVE -> skip-propagate
            }
        }
        return new Frontier(ready, skip, verdicts, outputReached);
    }

    private static EdgeVerdict deriveVerdict(NodeFact source, WorkflowEdge edge) {
        if (source == null) {
            return EdgeVerdict.PENDING;
        }
        return switch (source.status()) {
            case COMPLETED -> source.kind() == OutcomeKind.BRANCH
                ? (source.chosenEdgeIds().contains(edge.id()) ? EdgeVerdict.ACTIVE : EdgeVerdict.SKIPPED)
                : EdgeVerdict.ACTIVE;
            case SKIPPED -> EdgeVerdict.SKIPPED;
            // RUNNING (still executing) and FAILED (incl. the persisted FAILED_RETRYABLE projected here) both leave
            // out-edges PENDING: the planner neither advances past nor skips the node, so a failure halts the branch
            // and waits for retry. Do NOT make FAILED propagate SKIPPED — that would let a join fire past a failure.
            case RUNNING, FAILED -> EdgeVerdict.PENDING;
        };
    }
}
