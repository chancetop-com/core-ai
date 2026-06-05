package ai.core.server.workflow.engine;

/**
 * How a completed node decided its out-edges. NORMAL activates all out-edges (parallel fan-out);
 * BRANCH activates only the chosen out-edges and skips the rest (IF/ELSE, classifier).
 *
 * @author Xander
 */
public enum OutcomeKind {
    NORMAL,
    BRANCH
}
