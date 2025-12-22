package ai.core.memory.longterm;

import ai.core.llm.LLMProvider;
import ai.core.memory.longterm.extraction.MemoryExtractor;

/**
 * Builder for creating LongTermMemory instances.
 * Provides sensible defaults for quick setup.
 *
 * @author xander
 */
public class LongTermMemoryBuilder {

    private LLMProvider llmProvider;
    private MemoryExtractor extractor;
    private LongTermMemoryConfig config;
    private LongTermMemoryStore store;

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

    public LongTermMemoryBuilder store(LongTermMemoryStore store) {
        this.store = store;
        return this;
    }

    public LongTermMemory build() {
        if (llmProvider == null) {
            throw new IllegalStateException("llmProvider is required for LongTermMemory");
        }

        if (config == null) {
            config = LongTermMemoryConfig.builder().build();
        }

        if (store == null) {
            store = new DefaultLongTermMemoryStore(config);
        }

        if (extractor == null) {
            extractor = createDefaultExtractor();
        }

        return new LongTermMemory(store, extractor, llmProvider, config);
    }

    private MemoryExtractor createDefaultExtractor() {
        return new DefaultMemoryExtractor(llmProvider);
    }
}
