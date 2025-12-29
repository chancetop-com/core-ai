package ai.core.benchmark.inference;

/**
 * author: lim chen
 * date: 2025/12/22
 * description:
 */
public interface InferenceHandle<T,R> {

    R handle(T item);
}
