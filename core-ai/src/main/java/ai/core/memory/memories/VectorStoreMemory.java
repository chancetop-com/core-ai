package ai.core.memory.memories;

import ai.core.document.Document;
import ai.core.document.Embedding;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.memory.Memory;
import ai.core.rag.SimilaritySearchRequest;
import ai.core.vectorstore.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Abstract base class for memory implementations that use vector stores.
 * Provides common embedding and similarity search functionality.
 *
 * @author Xander
 */
public abstract class VectorStoreMemory implements Memory {
    private static final Logger LOGGER = LoggerFactory.getLogger(VectorStoreMemory.class);

    protected final VectorStore vectorStore;
    protected final LLMProvider llmProvider;
    protected final int topK;
    protected final double threshold;

    protected VectorStoreMemory(VectorStore vectorStore, LLMProvider llmProvider, int topK, double threshold) {
        this.vectorStore = vectorStore;
        this.llmProvider = llmProvider;
        this.topK = topK;
        this.threshold = threshold;
    }

    @Override
    public List<Document> retrieve(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            Embedding embedding = embedText(query);
            return vectorStore.similaritySearch(SimilaritySearchRequest.builder()
                .embedding(embedding)
                .topK(topK)
                .threshold(threshold)
                .build());
        } catch (Exception e) {
            LOGGER.warn("Failed to retrieve from vector store memory", e);
            return List.of();
        }
    }

    @Override
    public void clear() {
        // Vector stores typically don't support easy clearing
        // Subclasses can override if their vector store supports it
        LOGGER.debug("Clear operation not supported for vector store memory: {}", getType());
    }

    @Override
    public boolean isEmpty() {
        // Vector stores don't easily support checking emptiness
        return false;
    }

    /**
     * Store text with its embedding in the vector store.
     */
    protected void storeWithEmbedding(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            Embedding embedding = embedText(text);
            vectorStore.add(List.of(new Document(text, embedding, null)));
        } catch (Exception e) {
            LOGGER.warn("Failed to store text in vector store memory", e);
        }
    }

    /**
     * Generate embedding for the given text.
     */
    protected Embedding embedText(String text) {
        var response = llmProvider.embeddings(new EmbeddingRequest(List.of(text)));
        return response.embeddings.getFirst().embedding;
    }
}
