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
     * Set namespace template for memory organization.
     * If not set, defaults to user-scoped namespace (user/{user_id}).
     *
     * <p>Examples:
     * <pre>{@code
     * .namespaceTemplate(NamespaceTemplate.USER_SCOPED)      // user/{user_id}
     * .namespaceTemplate(NamespaceTemplate.ORG_USER_SCOPED)  // {org_id}/{user_id}
     * .namespaceTemplate(NamespaceTemplate.of("app", "{tenant}", "{user}"))
     * }</pre>
     */
    public LongTermMemoryBuilder namespaceTemplate(NamespaceTemplate template) {
        this.namespaceTemplate = template;
        return this;
    }

    /**
     * Configure batch extraction trigger by turns.
     */
    public LongTermMemoryBuilder maxBufferTurns(int turns) {
        ensureConfigBuilder();
        this.config = LongTermMemoryConfig.builder()
            .maxBufferTurns(turns)
            .build();
        return this;
    }

    /**
     * Configure batch extraction trigger by tokens.
     */
    public LongTermMemoryBuilder maxBufferTokens(int tokens) {
        ensureConfigBuilder();
        this.config = LongTermMemoryConfig.builder()
            .maxBufferTokens(tokens)
            .build();
        return this;
    }

    /**
     * Enable or disable memory decay.
     */
    public LongTermMemoryBuilder enableDecay(boolean enable) {
        ensureConfigBuilder();
        this.config = LongTermMemoryConfig.builder()
            .enableDecay(enable)
            .build();
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

    private void ensureConfigBuilder() {
        if (config == null) {
            config = LongTermMemoryConfig.builder().build();
        }
    }

    /**
     * Create default LLM-based memory extractor.
     */
    private MemoryExtractor createDefaultExtractor() {
        return new DefaultMemoryExtractor(llmProvider);
    }
}
