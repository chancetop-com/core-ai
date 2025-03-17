package ai.core.document;

import ai.core.rag.Embedding;

import java.util.Map;
import java.util.stream.IntStream;

/**
 * @author stephen
 */
public class Document {
    public String id;
    public String content;
    public Embedding embedding;
    public Map<String, Object> extraField;

    public Document() {

    }

    public Document(String id, float[] vector) {
        this.id = id;
        this.embedding = new Embedding(IntStream.range(0, vector.length).mapToObj(i -> (double) vector[i]).toList());
    }
}
