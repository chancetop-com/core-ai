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
 * Simple memory implementation using vector store for long-term memory.
 *
 * @author stephen
 */
public class NaiveMemory extends Memory {
    VectorStore vectorStore;
    LLMProvider llmProvider;

    @Override
    public void add(String text) {
        // Not implemented
    }

    @Override
    public void clear() {
        // Not implemented
    }

    @Override
    public List<Document> list() {
        return List.of();
    }

    @Override
    public void extractAndSave(List<Message> conversation) {
        var context = conversation.stream()
            .map(v -> v.content)
            .reduce("", (a, b) -> a + "\n" + b);
        var memories = new DefaultLongTermMemoryExtractionAgent(llmProvider).extractMemories(context);
        var docs = memories.stream()
            .map(v -> new Document(v, llmProvider.embeddings(new EmbeddingRequest(List.of(v))).embeddings.getFirst().embedding, null))
            .toList();
        vectorStore.add(docs);
    }

    @Override
    public List<Document> retrieve(String query) {
        return List.of();
    }
}
