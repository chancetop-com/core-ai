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
    private NamespaceTemplate namespaceTemplate;

    /**
     * Set the LLM provider for embeddings and extraction.
     * Required.
     */
    public LongTermMemoryBuilder llmProvider(LLMProvider llmProvider) {
        this.llmProvider = llmProvider;
        return this;
    }

    /**
     * Set custom memory extractor.
     * If not set, a default LLM-based extractor will be used.
     */
    public LongTermMemoryBuilder extractor(MemoryExtractor extractor) {
        this.extractor = extractor;
        return this;
    }

    /**
     * Set custom configuration.
     * If not set, default configuration will be used.
     */
    public LongTermMemoryBuilder config(LongTermMemoryConfig config) {
        this.config = config;
        return this;
    }

    /**
     * Set custom store.
     * If not set, a default in-memory store will be created.
     */
    public LongTermMemoryBuilder store(LongTermMemoryStore store) {
        this.store = store;
        return this;
    }

    /**
     * Build the LongTermMemory instance.
     *
     * @return configured LongTermMemory
     * @throws IllegalStateException if llmProvider is not set
     */
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

        return new LongTermMemory(store, extractor, llmProvider, config, namespaceTemplate);
    }

    /**
     * Create default LLM-based memory extractor.
     */
    private MemoryExtractor createDefaultExtractor() {
        return new DefaultMemoryExtractor(llmProvider);
    }
}
