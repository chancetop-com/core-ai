package ai.core.benchmark.loader;

import java.nio.file.Path;

/**
 * author: lim chen
 * date: 2026/01/07
 * description: Interface for writing evaluation results to files
 *
 * @param <R> Result type
 */
public interface ResultWriter<R> {
    void writeResultToFile(Path filePath, R result);
}
