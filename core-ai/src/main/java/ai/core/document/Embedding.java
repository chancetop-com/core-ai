package ai.core.document;

import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
public record Embedding(List<Double> vectors) {
    public static Embedding of(float[] vectors) {
        var list = new ArrayList<Double>();
        for (var vector : vectors) {
            list.add((double) vector);
        }
        return new Embedding(list);
    }

    public static Embedding of(List<Float> vectors) {
        var list = new ArrayList<Double>();
        for (var vector : vectors) {
            list.add((double) vector);
        }
        return new Embedding(list);
    }

    public float[] toFloatArray() {
        var array = new float[vectors.size()];
        for (int i = 0; i < vectors.size(); i++) {
            array[i] = vectors.get(i).floatValue();
        }
        return array;
    }
}
