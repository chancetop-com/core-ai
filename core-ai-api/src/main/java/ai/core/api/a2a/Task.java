package ai.core.api.a2a;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class Task {
    @Property(name = "id")
    public String id;

    @Property(name = "contextId")
    public String contextId;

    @Property(name = "status")
    public TaskStatus status;

    @Property(name = "artifacts")
    public List<Artifact> artifacts;

    @Property(name = "history")
    public List<Message> history;
}
