package ai.core.server.workflow;

import java.util.List;

/**
 * The result an executor returns for one node. This is the only thing the engine learns about a node's
 * execution; it carries the durable control-flow fact (NORMAL vs the chosen branch edges), the output, and —
 * for AGENT/LLM nodes — the decoupled child AgentRun id that links the two-layer run.
 *
 * @author Xander
 */
public sealed interface NodeOutcome {
    /** Plain completion: every out-edge becomes ACTIVE (parallel fan-out). */
    record Normal(String output, String childRunId) implements NodeOutcome {
        public Normal(String output) {
            this(output, null);
        }
    }

    /** Branch completion: only the chosen out-edges become ACTIVE, the rest SKIPPED. */
    record Branch(String output, List<String> chosenEdgeIds) implements NodeOutcome {
        public Branch {
            chosenEdgeIds = List.copyOf(chosenEdgeIds);
        }
    }

    /** Failure that halts the branch. retryable distinguishes a transient fault from a deterministic error. */
    record Fail(String error, boolean retryable, String childRunId) implements NodeOutcome {
        public Fail(String error, boolean retryable) {
            this(error, retryable, null);
        }
    }
}
