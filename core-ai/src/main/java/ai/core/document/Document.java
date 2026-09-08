package ai.core.document;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * @author stephen
 */
public class Document {
    public static String toId(String text) {
        return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public String id;                    // Long primary keys are converted via String.valueOf
    public Embedding embedding;          // may be null when the vector field is not read back
    public String content;               // may be null for non-text scenarios (e.g. images)
    public Map<String, Object> extraField;
    public Double score;                 // similarity score, filled only by similaritySearch

    public Document(String id, Embedding embedding, String content, Map<String, Object> extraField) {
        this.id = id;
        this.embedding = embedding;
        this.content = content;
        this.extraField = extraField;
    }

    public Document(String content, Embedding embedding, Map<String, Object> extraField) {
        this.id = toId(content);
        this.embedding = embedding;
        this.content = content;
        this.extraField = extraField;
    }

    public Document(String id, Embedding embedding, String content, Map<String, Object> extraField, Double score) {
        this.id = id;
        this.embedding = embedding;
        this.content = content;
        this.extraField = extraField;
        this.score = score;
    }
}
