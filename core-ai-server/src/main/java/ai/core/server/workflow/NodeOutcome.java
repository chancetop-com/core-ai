package ai.core.server.workflow;

import ai.core.server.domain.ArtifactRef;
import ai.core.server.domain.WorkflowNodeTraceMetadata;

import java.util.List;

/**
 * The result an executor returns for one node. This is the only thing the engine learns about a node's
 * execution; it carries the durable control-flow fact (NORMAL vs the chosen branch edges), the output, and —
 * for AGENT/LLM nodes — the decoupled child AgentRun id that links the two-layer run.
 *
 * @author Xander
 */
public sealed interface NodeOutcome {
    /** Plain completion: every out-edge becomes ACTIVE (parallel fan-out). artifacts are downstream file
     *  references this node produced (empty for most nodes; AGENT/AGGREGATOR populate them). */
    record Normal(String output, String childRunId, List<ArtifactRef> artifacts,
                  WorkflowNodeTraceMetadata traceMetadata) implements NodeOutcome {
        public Normal {
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        }

        public Normal(String output) {
            this(output, null, List.of(), null);
        }

        public Normal(String output, String childRunId) {
            this(output, childRunId, List.of(), null);
        }

        public Normal(String output, String childRunId, List<ArtifactRef> artifacts) {
            this(output, childRunId, artifacts, null);
        }
    }

    /** Branch completion: only the chosen out-edges become ACTIVE, the rest SKIPPED. */
    record Branch(String output, List<String> chosenEdgeIds) implements NodeOutcome {
        public Branch {
            chosenEdgeIds = List.copyOf(chosenEdgeIds);
        }
    }

    /** Failure that halts the branch. retryable distinguishes a transient fault from a deterministic error. */
    record Fail(String error, boolean retryable, String childRunId,
                WorkflowNodeTraceMetadata traceMetadata) implements NodeOutcome {
        public Fail(String error, boolean retryable) {
            this(error, retryable, null, null);
        }

        public Fail(String error, boolean retryable, String childRunId) {
            this(error, retryable, childRunId, null);
        }
    }

    /** The node is parked waiting for human input — the run pauses (out-edges stay PENDING) until a resume call
     *  settles this node. {@code ask} is a rendered JSON snapshot (mode + prompt) the UI shows the human. */
    record Waiting(String ask) implements NodeOutcome {
    }

    /** The node submitted a decoupled child WorkflowRun and parked. The run pauses (out-edges stay PENDING) like
     *  {@link Waiting}, but the waker is the child run's terminal callback, not a human resume. {@code childRunId}
     *  links the two-layer run and is the handle for cascade-cancel. */
    record Suspended(String childRunId) implements NodeOutcome {
    }
}
