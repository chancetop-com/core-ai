package ai.core.llm.providers.inner;

import ai.core.document.Embedding;

import java.util.List;

/**
 * @author stephen
 */
public class EmbeddingResponse {
    public List<EmbeddingData> embeddings;
    public Usage usage;

    public EmbeddingResponse(List<EmbeddingData> embeddings, Usage usage) {
        this.embeddings = embeddings;
        this.usage = usage;
    }

    public static class EmbeddingData {
        public String text;
        public Embedding embedding;

        public EmbeddingData(String text, Embedding embedding) {
            this.text = text;
            this.embedding = embedding;
        }
    }
}
