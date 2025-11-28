package ai.core.memory.memories;

import ai.core.defaultagents.DefaultLongTermMemoryExtractionAgent;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.Message;
import ai.core.memory.MemoryType;
import ai.core.vectorstore.VectorStore;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Long-term memory implementation using vector store for persistence.
 * Extracts and stores two types of memories:
 * - Semantic Memory: Key facts and knowledge extracted from conversations
 * - Episodic Memory: Full conversation context as episodes
 *
 * Suitable for cross-session knowledge retention.
 *
 * @author Xander
 */
public class LongTermMemory extends VectorStoreMemory {
    private static final int DEFAULT_TOP_K = 10;
    private static final double DEFAULT_THRESHOLD = 0.75;
    private static final String FACT_PREFIX = "[Fact] ";
    private static final String EPISODE_PREFIX = "[Episode] ";

    public LongTermMemory(VectorStore vectorStore, LLMProvider llmProvider) {
        this(vectorStore, llmProvider, DEFAULT_TOP_K, DEFAULT_THRESHOLD);
    }

    public LongTermMemory(VectorStore vectorStore, LLMProvider llmProvider, int topK, double threshold) {
        super(vectorStore, llmProvider, topK, threshold);
    }

    @Override
    public String getType() {
        return MemoryType.LONG_TERM.getDisplayName();
    }

    @Override
    public void save(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        String conversationContext = formatConversation(messages);

        // Extract and save semantic memories (facts)
        saveSemanticMemories(conversationContext);

        // Save episodic memory (full context)
        saveEpisodicMemory(conversationContext);
    }

    private void saveSemanticMemories(String context) {
        var extractionAgent = new DefaultLongTermMemoryExtractionAgent(llmProvider);
        List<String> extractedFacts = extractionAgent.extractMemories(context);

        for (String fact : extractedFacts) {
            if (fact != null && !fact.isBlank()) {
                storeWithEmbedding(FACT_PREFIX + fact);
            }
        }
    }

    private void saveEpisodicMemory(String context) {
        if (context != null && !context.isBlank()) {
            storeWithEmbedding(EPISODE_PREFIX + context);
        }
    }

    private String formatConversation(List<Message> messages) {
        return messages.stream()
            .filter(msg -> msg.content != null && !msg.content.isEmpty())
            .map(msg -> msg.content)
            .collect(Collectors.joining("\n"));
    }
}
