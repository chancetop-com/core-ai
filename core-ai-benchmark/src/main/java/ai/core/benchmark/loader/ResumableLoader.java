package ai.core.benchmark.loader;

import java.util.List;

/**
 * author: lim chen
 * date: 2026/01/07
 * description: Interface for loaders that support resuming from completed items
 */
public interface ResumableLoader<P, T> extends DatasetLoader<P, T> {

    List<T> loadUncompleted(P params);
}
