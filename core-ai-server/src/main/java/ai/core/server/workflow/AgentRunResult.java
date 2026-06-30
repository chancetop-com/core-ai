package ai.core.server.workflow;

import ai.core.server.domain.ArtifactRef;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TokenUsage;

import java.util.List;

/**
 * The terminal result of a child AgentRun, as the workflow node needs it: completed (with output and any
 * artifact references) or failed (with an error). Token usage / transcript stay on the child AgentRun and are
 * linked via child_run_id; artifacts are lifted here as lean {@link ArtifactRef}s (references, never bytes) so
 * the node can expose them downstream via {@code nodes.<id>.artifacts}.
 *
 * @author Xander
 */
public record AgentRunResult(boolean completed, String output, String error, List<ArtifactRef> artifacts,
                             String traceId, RunStatus status, TokenUsage tokenUsage) {
    public AgentRunResult {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    public static AgentRunResult completed(String output) {
        return completed(output, List.of());
    }

    public static AgentRunResult completed(String output, List<ArtifactRef> artifacts) {
        return completed(output, artifacts, null, null, null);
    }

    public static AgentRunResult completed(String output, List<ArtifactRef> artifacts, String traceId,
                                           RunStatus status, TokenUsage tokenUsage) {
        return new AgentRunResult(true, output, null, artifacts, traceId, status, tokenUsage);
    }

    public static AgentRunResult failed(String error) {
        return failed(error, null, null, null);
    }

    public static AgentRunResult failed(String error, String traceId, RunStatus status, TokenUsage tokenUsage) {
        return new AgentRunResult(false, null, error, List.of(), traceId, status, tokenUsage);
    }
}
