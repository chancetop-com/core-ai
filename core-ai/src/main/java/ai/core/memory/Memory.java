package ai.core.memory;

import java.util.List;

/**
 * @author stephen
 */
public interface Memory {
    void add(String memory);

    void clear();

    List<String> list();

    default boolean isEmpty() {
        return list().isEmpty();
    }
}
