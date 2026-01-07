package ai.core.memory;

import ai.core.llm.LLMProvider;

/**
 * Builder for Memory.
 *
 * @author xander
 */
public class MemoryBuilder {

    private LLMProvider llmProvider;
    private MemoryStore memoryStore;
    private int defaultTopK = 5;

    public MemoryBuilder llmProvider(LLMProvider llmProvider) {
        this.llmProvider = llmProvider;
        return this;
    }

    public MemoryBuilder memoryStore(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
        return this;
    }

    public MemoryBuilder store(MemoryStore store) {
        return memoryStore(store);
    }

    public MemoryBuilder defaultTopK(int defaultTopK) {
        this.defaultTopK = defaultTopK;
        return this;
    }

    public Memory build() {
        if (llmProvider == null) {
            throw new IllegalStateException("llmProvider is required for Memory");
        }

        if (memoryStore == null) {
            memoryStore = new InMemoryStore();
        }

        return new Memory(memoryStore, llmProvider, defaultTopK);
    }
}
