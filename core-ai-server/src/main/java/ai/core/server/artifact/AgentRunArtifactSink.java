package ai.core.server.artifact;

import ai.core.server.domain.AgentRun;
import ai.core.server.domain.AgentRunArtifact;
import core.framework.mongo.MongoCollection;

import java.util.ArrayList;

/**
 * @author xander
 */
public final class AgentRunArtifactSink implements ArtifactSink {
    private final String runId;
    private final MongoCollection<AgentRun> agentRunCollection;

    public AgentRunArtifactSink(String runId, MongoCollection<AgentRun> agentRunCollection) {
        this.runId = runId;
        this.agentRunCollection = agentRunCollection;
    }

    @Override
    public void append(AgentRunArtifact artifact) {
        var run = agentRunCollection.get(runId)
            .orElseThrow(() -> new RuntimeException("run not found, id=" + runId));
        var artifacts = run.artifacts != null ? new ArrayList<>(run.artifacts) : new ArrayList<AgentRunArtifact>();
        artifacts.add(artifact);
        run.artifacts = artifacts;
        agentRunCollection.replace(run);
    }
}
