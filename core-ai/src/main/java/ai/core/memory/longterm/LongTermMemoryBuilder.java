package ai.core.memory.longterm;

import ai.core.llm.LLMProvider;
import ai.core.memory.history.ChatHistoryProvider;
import ai.core.memory.longterm.extraction.MemoryExtractor;

/**
 * Builder for LongTermMemory.
 *
 * @author xander
 */
public class LongTermMemoryBuilder {

    private LLMProvider llmProvider;
    private MemoryExtractor extractor;
    private LongTermMemoryConfig config;
    private MemoryStore memoryStore;
    private ChatHistoryProvider historyProvider;

    public LongTermMemoryBuilder llmProvider(LLMProvider llmProvider) {
        this.llmProvider = llmProvider;
        return this;
    }

    public LongTermMemoryBuilder extractor(MemoryExtractor extractor) {
        this.extractor = extractor;
        return this;
    }

    public LongTermMemoryBuilder config(LongTermMemoryConfig config) {
        this.config = config;
        return this;
    }

    public LongTermMemoryBuilder memoryStore(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
        return this;
    }

    public LongTermMemoryBuilder store(MemoryStore store) {
        return memoryStore(store);
    }

    public LongTermMemoryBuilder historyProvider(ChatHistoryProvider historyProvider) {
        this.historyProvider = historyProvider;
        return this;
    }

    public LongTermMemory build() {
        if (llmProvider == null) {
            throw new IllegalStateException("llmProvider is required for LongTermMemory");
        }

        if (historyProvider == null) {
            throw new IllegalStateException("historyProvider is required for LongTermMemory");
        }

        if (config == null) {
            config = LongTermMemoryConfig.builder().build();
        }

        if (memoryStore == null) {
            memoryStore = new InMemoryStore();
        }

        if (extractor == null) {
            extractor = createDefaultExtractor();
        }

        return new LongTermMemory(memoryStore, historyProvider, extractor, llmProvider, config);
    }

    private MemoryExtractor createDefaultExtractor() {
        return new DefaultMemoryExtractor(llmProvider);
    }
}
