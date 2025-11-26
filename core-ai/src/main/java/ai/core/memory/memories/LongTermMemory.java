package ai.core.memory.memories;

import ai.core.defaultagents.DefaultLongTermMemoryExtractionAgent;
import ai.core.document.Document;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.Message;
import ai.core.memory.Memory;
import ai.core.vectorstore.VectorStore;
import ai.core.rag.SimilaritySearchRequest;

import java.util.List;
import java.util.stream.Collectors;

public class LongTermMemory extends Memory {
    private final VectorStore vectorStore;
    private final LLMProvider llmProvider;

    public LongTermMemory(VectorStore vectorStore, LLMProvider llmProvider) {
        this.vectorStore = vectorStore;
        this.llmProvider = llmProvider;
    }

    @Override
    public void extractAndSave(List<Message> conversation) {
        if (conversation == null || conversation.isEmpty()) return;

        String context = conversation.stream()
                .map(v -> v.content)
                .collect(Collectors.joining("\n"));

        // 1. Semantic Memory: Extract Facts
        saveSemanticMemory(context);

        // 2. Episodic Memory: Save Context/Result
        saveEpisodicMemory(context);
    }

    private void saveSemanticMemory(String context) {
        // Use the specialized agent to extract facts
        var memories = new DefaultLongTermMemoryExtractionAgent(llmProvider).extractMemories(context);
        
        // Save extracted facts
        for (String memory : memories) {
            add("Fact: " + memory);
        }
    }

    private void saveEpisodicMemory(String context) {
        // Save the full context as an episode
        // We might want to add metadata like timestamp, but for now we just tag it
        add("Episode: " + context);
    }

    @Override
    public List<Document> retrieve(String query) {
        var embeddingResponse = llmProvider.embeddings(new EmbeddingRequest(List.of(query)));
        var embedding = embeddingResponse.embeddings.getFirst().embedding;

        // Retrieve both facts and episodes
        return vectorStore.similaritySearch(SimilaritySearchRequest.builder()
                .embedding(embedding)
                .topK(10) 
                .threshold(0.75)
                .build());
    }

    @Override
    public void add(String text) {
        var embeddingResponse = llmProvider.embeddings(new EmbeddingRequest(List.of(text)));
        var embedding = embeddingResponse.embeddings.getFirst().embedding;
        vectorStore.add(List.of(new Document(text, embedding, null)));
    }

    @Override
    public void clear() {
        // No-op
    }

    @Override
    public List<Document> list() {
        return List.of();
    }
}
