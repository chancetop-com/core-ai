package ai.core.server.workflow.engine;

import java.util.Map;
import java.util.Set;

/**
 * The pure result of one {@link Planner#plan} pass: the nodes to dispatch, the nodes to mark SKIPPED, the
 * derived edge verdicts (for observability/tests), and whether the run reached an END.
 *
 * @author Xander
 */
public record Frontier(Set<String> readyNodeIds, Set<String> skipNodeIds, Map<String, EdgeVerdict> edgeVerdicts, boolean terminal) {
    public Frontier {
        readyNodeIds = Set.copyOf(readyNodeIds);
        skipNodeIds = Set.copyOf(skipNodeIds);
        edgeVerdicts = Map.copyOf(edgeVerdicts);
    }

    /** True when this pass advances the run; false means terminal or stuck (the runner decides which). */
    public boolean hasProgress() {
        return !readyNodeIds.isEmpty() || !skipNodeIds.isEmpty();
    }
}
