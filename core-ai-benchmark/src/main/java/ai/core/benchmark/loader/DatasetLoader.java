package ai.core.benchmark.loader;

import java.util.List;

/**
 * author: lim chen
 * date: 2026/01/07
 * description: Base interface for dataset loaders
 */
public interface DatasetLoader<P, T> {
    List<T> load(P params);
}
