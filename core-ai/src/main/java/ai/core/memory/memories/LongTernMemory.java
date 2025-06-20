package ai.core.memory.memories;

import ai.core.memory.Memory;
import core.framework.util.Lists;

import java.util.List;

/**
 * @author stephen
 */
public class LongTernMemory implements Memory {
    public static final String TEMPLATE = "\n\nLong Term Memory:\n";
    private final List<String> memories = Lists.newArrayList();

    @Override
    public void add(String memory) {
        memories.add(memory);
    }

    @Override
    public void clear() {
        memories.clear();
    }

    @Override
    public List<String> list() {
        return memories;
    }

    @Override
    public String toString() {
        return String.join("\n", list());
    }
}
