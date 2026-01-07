package ai.core.benchmark.loader;

import java.util.List;

/**
 * author: lim chen
 * date: 2026/01/07
 * description: Interface for loaders that support resuming from completed items
 * @param <P> Parameter type for loading (e.g., category, path, filter criteria)
 * @param <T> Data type returned by the loader
 */
public interface ResumableLoader<P, T> extends DatasetLoader<P, T> {
    /**
     * Load only uncompleted items, skipping items that have already been processed
     * @param params parameters for loading (e.g., category, path, filter)
     * @return list of uncompleted data items
     */
    List<T> loadUncompleted(P params);
}
