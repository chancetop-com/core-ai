package ai.core.memory;

/**
 * @author xander
 */
public interface MemoryProvider {
    String load();

    String readRaw();

    String edit(String oldString, String newString);
}
