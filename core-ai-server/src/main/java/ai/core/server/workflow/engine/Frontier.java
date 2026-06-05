package ai.core.server.workflow.engine;

import java.util.Map;
import java.util.Set;

/**
 * The pure result of one {@link Planner#plan} pass: the nodes to dispatch, the nodes to mark SKIPPED, the
 * derived edge verdicts (for observability/tests), and whether an output (END) has been reached.
 *
 * <p>Run completion is NOT {@code outputReached}. A run finishes only when the frontier is exhausted
 * ({@code !hasProgress()}) AND nothing is in flight; {@code outputReached} then classifies success (an END
 * completed) vs stuck (e.g. halted on a failed node). Under parallel fan-out an early sink can complete
 * while other branches still have work, so {@code outputReached} and {@code hasProgress} may both be true.
 *
 * @author Xander
 */
public record Frontier(Set<String> readyNodeIds, Set<String> skipNodeIds, Map<String, EdgeVerdict> edgeVerdicts, boolean outputReached) {
    public Frontier {
        readyNodeIds = Set.copyOf(readyNodeIds);
        skipNodeIds = Set.copyOf(skipNodeIds);
        edgeVerdicts = Map.copyOf(edgeVerdicts);
    }

    /** True while this pass still has nodes to dispatch or skip. When false (and nothing is in flight) the run is done. */
    public boolean hasProgress() {
        return !readyNodeIds.isEmpty() || !skipNodeIds.isEmpty();
    }
}
