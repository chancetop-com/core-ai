package ai.core.task;

import ai.core.task.parts.TextPart;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public record TaskArtifact(String name,
                           String description,
                           List<Part<?>> parts,
                           Map<String, String> metadata,
                           Integer index,
                           Boolean append,
                           Boolean lastChunk) {
    public static TaskArtifact of(String name,
                                  String description,
                                  Map<String, String> metadata,
                                  String text,
                                  Boolean append,
                                  Boolean lastChunk) {
        return new TaskArtifact(name, description, List.of(new TextPart(text)), metadata, 0, append, lastChunk);
    }
}
