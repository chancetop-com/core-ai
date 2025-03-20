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

    public String id;
    public Embedding embedding;
    public String content;
    public Map<String, Object> extraField;

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
}
