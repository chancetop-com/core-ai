package ai.core.server.artifact;

import ai.core.server.domain.AgentRun;
import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.project.ProjectArtifactBinder;
import ai.core.server.project.ProjectAttributionStore;
import core.framework.mongo.MongoCollection;

import java.util.ArrayList;

/**
 * @author xander
 */
public final class AgentRunArtifactSink implements ArtifactSink {
    private final String runId;
    private final MongoCollection<AgentRun> agentRunCollection;
    private final ProjectArtifactBinder binder;

    public AgentRunArtifactSink(String runId, MongoCollection<AgentRun> agentRunCollection) {
        this(runId, agentRunCollection, null);
    }

    public AgentRunArtifactSink(String runId, MongoCollection<AgentRun> agentRunCollection, ProjectArtifactBinder binder) {
        this.runId = runId;
        this.agentRunCollection = agentRunCollection;
        this.binder = binder;
    }

    @Override
    public void append(AgentRunArtifact artifact) {
        var run = agentRunCollection.get(runId)
            .orElseThrow(() -> new RuntimeException("run not found, id=" + runId));
        var artifacts = run.artifacts != null ? new ArrayList<>(run.artifacts) : new ArrayList<AgentRunArtifact>();
        artifacts.add(artifact);
        run.artifacts = artifacts;
        agentRunCollection.replace(run);
        // project attribution is inherited from the run (schedule binding or attributor) once persisted
        if (binder != null) binder.onArtifact(ProjectAttributionStore.TARGET_RUN, runId, artifact.fileId, run.agentId, artifact.createdAt);
    }
}
