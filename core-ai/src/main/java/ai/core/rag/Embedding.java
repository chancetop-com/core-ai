package ai.core.rag;

import java.util.List;

/**
 * @author stephen
 */
public record Embedding(List<Double> vectors) {
    public float[] toFloatArray() {
        var array = new float[vectors.size()];
        for (int i = 0; i < vectors.size(); i++) {
            array[i] = vectors.get(i).floatValue();
        }
        return array;
    }
}
