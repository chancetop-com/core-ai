package ai.core.memory;

import ai.core.llm.LLMProvider;
import ai.core.memory.extraction.MemoryExtractor;
import ai.core.memory.history.ChatHistoryProvider;

/**
 * Builder for Memory.
 *
 * @author xander
 */
public class MemoryBuilder {

    private LLMProvider llmProvider;
    private MemoryExtractor extractor;
    private MemoryConfig config;
    private MemoryStore memoryStore;
    private ChatHistoryProvider historyProvider;

    public MemoryBuilder llmProvider(LLMProvider llmProvider) {
        this.llmProvider = llmProvider;
        return this;
    }

    public MemoryBuilder extractor(MemoryExtractor extractor) {
        this.extractor = extractor;
        return this;
    }

    public MemoryBuilder config(MemoryConfig config) {
        this.config = config;
        return this;
    }

    public MemoryBuilder memoryStore(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
        return this;
    }

    public MemoryBuilder store(MemoryStore store) {
        return memoryStore(store);
    }

    public MemoryBuilder historyProvider(ChatHistoryProvider historyProvider) {
        this.historyProvider = historyProvider;
        return this;
    }

    public Memory build() {
        if (llmProvider == null) {
            throw new IllegalStateException("llmProvider is required for Memory");
        }

        if (historyProvider == null) {
            throw new IllegalStateException("historyProvider is required for Memory");
        }

        if (config == null) {
            config = MemoryConfig.builder().build();
        }

        if (memoryStore == null) {
            memoryStore = new InMemoryStore();
        }

        if (extractor == null) {
            extractor = createDefaultExtractor();
        }

        return new Memory(memoryStore, historyProvider, extractor, llmProvider, config);
    }

    private MemoryExtractor createDefaultExtractor() {
        return new DefaultMemoryExtractor(llmProvider);
    }
}
