package ai.core.rag.vectorstore.hnswlib;

import com.github.jelmerk.hnswlib.core.Item;

import java.io.Serial;

/**
 * @author stephen
 */
public class HnswDocument implements Item<String, float[]> {
    @Serial
    private static final long serialVersionUID = -8900432478698891085L;

    String id;
    float[] vector;
    int dimensions;

    public HnswDocument(String id, float[] vector, int dimensions) {
        this.id = id;
        this.vector = vector;
        this.dimensions = dimensions;
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
        return dimensions;
    }
}
