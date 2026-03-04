package ai.core.memory;

/**
 * @author xander
 */
public interface MemoryProvider {
    String load();

    void save(String content);

    int remove(String keyword);
}
