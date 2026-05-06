package ai.core.api.a2a;

import core.framework.api.json.Property;

import java.util.Map;

/**
 * Streaming update for an artifact produced by a task.
 *
 * @author xander
 */
public class TaskArtifactUpdateEvent {
    @Property(name = "taskId")
    public String taskId;

    @Property(name = "contextId")
    public String contextId;

    @Property(name = "artifact")
    public Artifact artifact;

    @Property(name = "append")
    public Boolean append;

    @Property(name = "lastChunk")
    public Boolean lastChunk;

    @Property(name = "metadata")
    public Map<String, Object> metadata;
}
