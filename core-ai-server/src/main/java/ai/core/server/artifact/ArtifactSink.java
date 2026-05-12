package ai.core.server.artifact;

import ai.core.server.domain.AgentRunArtifact;

/**
 * Persists artifacts submitted by SubmitArtifactsTool. Implementations decide where the artifact lands
 * (e.g. an agent run record, a chat session record).
 *
 * @author xander
 */
public interface ArtifactSink {
    void append(AgentRunArtifact artifact);
}
