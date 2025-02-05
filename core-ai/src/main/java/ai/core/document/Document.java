package ai.core.document;

import ai.core.rag.Embedding;

import java.util.Map;

/**
 * @author stephen
 */
public class Document {
    public String id;
    public String content;
    public Embedding embedding;
    public Map<String, Object> extraField;
}
