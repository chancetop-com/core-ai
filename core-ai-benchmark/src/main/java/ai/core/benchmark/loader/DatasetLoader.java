package ai.core.benchmark.loader;

import java.util.List;

/**
 * author: lim chen
 * date: 2026/01/07
 * description: Base interface for dataset loaders
 * @param <P> Parameter type for loading (e.g., category, path, filter criteria)
 * @param <T> Data type returned by the loader
 */
public interface DatasetLoader<P, T> {
    /**
     * Load dataset based on the given parameters
     * @param params parameters for loading (e.g., category, path, filter)
     * @return list of loaded data
     */
    List<T> load(P params);
}
