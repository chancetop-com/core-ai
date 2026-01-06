package ai.core.memory.longterm;

import ai.core.llm.LLMProvider;
import ai.core.memory.history.ChatHistoryStore;
import ai.core.memory.history.InMemoryChatHistoryStore;
import ai.core.memory.longterm.extraction.MemoryExtractor;

/**
 * @author xander
 */
public class LongTermMemoryBuilder {

    private LLMProvider llmProvider;
    private MemoryExtractor extractor;
    private LongTermMemoryConfig config;
    private MemoryStore memoryStore;
    private ChatHistoryStore chatHistoryStore;

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

    public LongTermMemoryBuilder chatHistoryStore(ChatHistoryStore chatHistoryStore) {
        this.chatHistoryStore = chatHistoryStore;
        return this;
    }

    public LongTermMemory build() {
        if (llmProvider == null) {
            throw new IllegalStateException("llmProvider is required for LongTermMemory");
        }

        if (config == null) {
            config = LongTermMemoryConfig.builder().build();
        }

        if (memoryStore == null) {
            memoryStore = new InMemoryStore();
        }

        if (chatHistoryStore == null) {
            chatHistoryStore = new InMemoryChatHistoryStore();
        }

        if (extractor == null) {
            extractor = createDefaultExtractor();
        }

        return new LongTermMemory(memoryStore, chatHistoryStore, extractor, llmProvider, config);
    }

    private MemoryExtractor createDefaultExtractor() {
        return new DefaultMemoryExtractor(llmProvider);
    }
}
