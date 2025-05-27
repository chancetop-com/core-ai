package ai.core.llm.domain;

import ai.core.document.Embedding;
import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class EmbeddingResponse {
    public static EmbeddingResponse of(List<EmbeddingData> embeddings, Usage usage) {
        var response = new EmbeddingResponse();
        response.embeddings = embeddings;
        response.usage = usage;
        return response;
    }

    @Property(name = "embeddings")
    public List<EmbeddingData> embeddings;
    @Property(name = "usage")
    public Usage usage;

    public static class EmbeddingData {
        public static EmbeddingData of(String text, Embedding embedding) {
            var data = new EmbeddingData();
            data.text = text;
            data.embedding = embedding;
            return data;
        }

        @Property(name = "text")
        public String text;
        @Property(name = "embedding")
        public Embedding embedding;
    }
}
