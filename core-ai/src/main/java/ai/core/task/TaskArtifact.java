package ai.core.task;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class TaskArtifact {
    public String name;
    public String description;
    public List<Part<?>> parts;
    public Map<String, String> metadata;
    public Integer index;
    public Boolean append;
    public Boolean lastChunk;
}
