package ai.core.rag.vectorstore.hnswlib;

import com.github.jelmerk.hnswlib.core.Item;

import java.io.Serial;
import java.util.Map;

/**
 * @author stephen
 */
public class HnswDocument implements Item<String, float[]> {
    @Serial
    private static final long serialVersionUID = -8900432478698891085L;

    private final String id;
    private final float[] vector;
    private final String content;
    private final Map<String, Object> extraField;

    public HnswDocument(String id, float[] vector, String content, Map<String, Object> extraField) {
        this.id = id;
        this.vector = vector;
        this.content = content;
        this.extraField = extraField;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public float[] vector() {
        return vector;
    }

    @Override
    public int dimensions() {
        return vector.length;
    }

    public String content() {
        return content;
    }

    public Map<String, Object> extraField() {
        return extraField;
    }
}
