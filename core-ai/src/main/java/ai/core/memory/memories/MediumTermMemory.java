package ai.core.memory.memories;

import ai.core.llm.LLMProvider;
import ai.core.llm.domain.Message;
import ai.core.memory.MemoryType;
import ai.core.memory.compression.CompressionStrategy;
import ai.core.memory.compression.LLMCompressionStrategy;
import ai.core.vectorstore.VectorStore;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Medium-term memory implementation using vector store for persistence.
 * Stores both full conversation logs and summaries for semantic retrieval.
 * Suitable for session-level context that spans multiple interactions.
 *
 * @author Xander
 */
public class MediumTermMemory extends VectorStoreMemory {
    private static final int DEFAULT_TOP_K = 5;
    private static final double DEFAULT_THRESHOLD = 0.7;
    private static final String SUMMARY_PREFIX = "[Session Summary] ";
    private static final String CONVERSATION_PREFIX = "[Conversation Log] ";

    private final CompressionStrategy compressionStrategy;

    public MediumTermMemory(VectorStore vectorStore, LLMProvider llmProvider) {
        this(vectorStore, llmProvider, DEFAULT_TOP_K, DEFAULT_THRESHOLD);
    }

    public MediumTermMemory(VectorStore vectorStore, LLMProvider llmProvider, int topK, double threshold) {
        super(vectorStore, llmProvider, topK, threshold);
        this.compressionStrategy = new LLMCompressionStrategy(llmProvider);
    }

    public MediumTermMemory(VectorStore vectorStore, LLMProvider llmProvider,
                            int topK, double threshold, CompressionStrategy compressionStrategy) {
        super(vectorStore, llmProvider, topK, threshold);
        this.compressionStrategy = compressionStrategy;
    }

    @Override
    public String getType() {
        return MemoryType.MEDIUM_TERM.getDisplayName();
    }

    @Override
    public void save(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        String conversationText = formatConversation(messages);
        String summary = compressionStrategy.compress(conversationText);

        // Store full conversation log for detailed retrieval
        storeWithEmbedding(CONVERSATION_PREFIX + conversationText);

        // Store summary for quick semantic matching
        if (summary != null && !summary.isBlank()) {
            storeWithEmbedding(SUMMARY_PREFIX + summary);
        }
    }

    private String formatConversation(List<Message> messages) {
        return messages.stream()
            .filter(msg -> msg.content != null && !msg.content.isEmpty())
            .map(msg -> msg.role + ": " + msg.content)
            .collect(Collectors.joining("\n"));
    }
}
