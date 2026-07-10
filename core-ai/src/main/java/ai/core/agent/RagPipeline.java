package ai.core.agent;

import ai.core.defaultagents.DefaultRagQueryRewriteAgent;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.Usage;
import ai.core.rag.RagConfig;
import ai.core.rag.SimilaritySearchRequest;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * @author stephen
 */
final class RagPipeline {

    static void execute(RagConfig ragConfig, String query, Map<String, Object> variables, Consumer<Usage> tokenCostTracker) {
        if (ragConfig.vectorStore() == null || ragConfig.llmProvider() == null)
            throw new RuntimeException("vectorStore/llmProvider cannot be null if useRag flag is enabled");
        var ragQuery = query;
        if (ragConfig.enableQueryRewriting()) {
            ragQuery = DefaultRagQueryRewriteAgent.of(ragConfig.llmProvider()).run(query);
        }
        var rsp = ragConfig.llmProvider().embeddings(new EmbeddingRequest(List.of(ragQuery)));
        tokenCostTracker.accept(rsp.usage);
        var embedding = rsp.embeddings.getFirst().embedding;
        var docs = ragConfig.vectorStore().similaritySearch(SimilaritySearchRequest.builder()
                .embedding(embedding)
                .threshold(ragConfig.threshold())
                .topK(ragConfig.topK()).build());
        var context = ragConfig.llmProvider().rerankings(RerankingRequest.of(ragQuery, docs.stream().map(v -> v.content).toList())).rerankedDocuments.getFirst();
        variables.put(RagConfig.AGENT_RAG_CONTEXT_PLACEHOLDER, context);
    }

    private RagPipeline() {
    }
}
