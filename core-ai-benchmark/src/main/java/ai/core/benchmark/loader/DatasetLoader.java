package ai.core.benchmark.loader;

import java.util.List;

/**
 * author: lim chen
 * date: 2025/12/19
 * description:
 */
public interface DatasetLoader<T, U> {

    void load();

    List<T> getAllItems();

    List<List<T>> splitDataset(int num);

    List<T> getLimitItems(int limit);

    U getOneAnswer(String itemId);

    void officialization(String outPath);
}
