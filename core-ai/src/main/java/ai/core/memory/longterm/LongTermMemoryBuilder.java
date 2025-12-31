package ai.core.memory.longterm;

import ai.core.llm.LLMProvider;
import ai.core.memory.conflict.MemoryConflictResolver;
import ai.core.memory.longterm.extraction.MemoryExtractor;

/**
 * @author xander
 */
public class LongTermMemoryBuilder {

    private LLMProvider llmProvider;
    private MemoryExtractor extractor;
    private LongTermMemoryConfig config;
    private MemoryStore store;
    private MemoryConflictResolver conflictResolver;

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

    public LongTermMemoryBuilder store(MemoryStore store) {
        this.store = store;
        return this;
    }

    public LongTermMemoryBuilder conflictResolver(MemoryConflictResolver conflictResolver) {
        this.conflictResolver = conflictResolver;
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
            store = new InMemoryStore();
        }

        if (extractor == null) {
            extractor = createDefaultExtractor();
        }

        MemoryConflictResolver resolver = this.conflictResolver;
        if (resolver == null && config.isEnableConflictResolution()) {
            resolver = new MemoryConflictResolver(llmProvider, null);
        }

        return new LongTermMemory(store, extractor, llmProvider, config, resolver);
    }

    private MemoryExtractor createDefaultExtractor() {
        return new DefaultMemoryExtractor(llmProvider);
    }
}
