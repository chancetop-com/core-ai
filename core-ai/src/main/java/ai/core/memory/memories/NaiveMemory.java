package ai.core.memory.memories;

import ai.core.defaultagents.DefaultLongTermMemoryExtractionAgent;
import ai.core.document.Document;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.Message;
import ai.core.memory.Memory;
import ai.core.vectorstore.VectorStore;

import java.util.List;

/**
 * Legacy naive memory implementation.
 * Consider using {@link LongTermMemory} for new implementations.
 *
 * @author Xander
 * @deprecated Use {@link LongTermMemory} instead
 */
@Deprecated
public class NaiveMemory implements Memory {
    /**
     * Memory prompt template constant for backward compatibility.
     */
    public static final String PROMPT_MEMORY_TEMPLATE = "\n\n### Memory\n";

    private final VectorStore vectorStore;
    private final LLMProvider llmProvider;

    public NaiveMemory(VectorStore vectorStore, LLMProvider llmProvider) {
        this.vectorStore = vectorStore;
        this.llmProvider = llmProvider;
    }

    @Override
    public String getType() {
        return "Naive Memory";
    }

    @Override
    public void save(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        String context = messages.stream()
            .map(msg -> msg.content)
            .reduce("", (a, b) -> a + "\n" + b);
        List<String> memories = new DefaultLongTermMemoryExtractionAgent(llmProvider).extractMemories(context);
        List<Document> docs = memories.stream()
            .map(memory -> new Document(
                memory,
                llmProvider.embeddings(new EmbeddingRequest(List.of(memory))).embeddings.getFirst().embedding,
                null
            ))
            .toList();
        vectorStore.add(docs);
    }

    @Override
    public List<Document> retrieve(String query) {
        return List.of();
    }

    @Override
    public void clear() {
        // No-op
    }
}
