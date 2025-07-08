package ai.core.vectorstore.vectorstores.hnswlib;

import com.github.jelmerk.hnswlib.core.Item;

import java.io.Serial;

/**
 * @author stephen
 */
public record HnswDocument(String id,
                           float[] vector,
                           String content,
                           String extraField) implements Item<String, float[]> {
    @Serial
    private static final long serialVersionUID = -8900432478698891085L;

    @Override
    public int dimensions() {
        return vector.length;
    }
}
