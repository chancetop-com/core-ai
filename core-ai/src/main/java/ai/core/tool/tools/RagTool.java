package ai.core.tool.tools;

import ai.core.defaultagents.DefaultRagQueryRewriteAgent;
import ai.core.document.Document;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.RerankingRequest;
import ai.core.rag.RagConfig;
import ai.core.rag.SimilaritySearchRequest;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.vectorstore.VectorStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG (Retrieval-Augmented Generation) Tool
 * This tool allows agents to perform RAG queries on demand,
 * searching for relevant context from vector stores based on similarity.
 * The tool executes the complete RAG pipeline:
 * 1. Query rewriting for better retrieval (optional, enabled by default)
 * 2. Embedding generation
 * 3. Similarity search in vector store
 * 4. Document reranking for relevance
 *
 * Note: llmProvider is always required for embedding generation and document reranking.
 * Query rewriting can be disabled via enableQueryRewriting(false) in the builder.
 */
public class RagTool extends ToolCall {

    // Builder pattern for convenient creation
    public static Builder builder() {
        return new Builder();
    }

    private final VectorStore vectorStore;
    private final LLMProvider llmProvider;
    private Integer defaultTopK = 5;
    private Double defaultThreshold = 0d;
    private boolean enableQueryRewriting = true;  // Default enabled for backward compatibility

    public RagTool(VectorStore vectorStore, LLMProvider llmProvider) {
        if (vectorStore == null || llmProvider == null) {
            throw new IllegalArgumentException("vectorStore and llmProvider cannot be null");
        }
        this.vectorStore = vectorStore;
        this.llmProvider = llmProvider;
    }

    public RagTool(RagConfig ragConfig) {
        if (ragConfig == null || ragConfig.vectorStore() == null || ragConfig.llmProvider() == null) {
            throw new IllegalArgumentException("RagConfig with valid vectorStore and llmProvider is required");
        }
        this.vectorStore = ragConfig.vectorStore();
        this.llmProvider = ragConfig.llmProvider();
        this.defaultTopK = ragConfig.topK() != null ? ragConfig.topK() : 5;
        this.defaultThreshold = ragConfig.threshold() != null ? ragConfig.threshold() : 0d;
    }

    @Override
    public String call(String text) {
        try {
            // Parse input parameters
            ObjectMapper mapper = new ObjectMapper();
            JsonNode params = mapper.readTree(text);

            String query = params.has("query") ? params.get("query").asText() : "";
            if (query.isEmpty()) {
                return "Error: 'query' parameter is required";
            }

            // Get optional parameters with defaults
            int topK = params.has("topK") ? params.get("topK").asInt() : defaultTopK;
            double threshold = params.has("threshold") ? params.get("threshold").asDouble() : defaultThreshold;

            // Execute RAG pipeline
            return executeRag(query, topK, threshold);

        } catch (Exception e) {
            return "Error executing RAG query: " + e.getMessage();
        }
    }

    private String executeRag(String query, int topK, double threshold) {
        // Step 1: Query rewriting for better retrieval (optional)
        String ragQuery = query;
        if (enableQueryRewriting) {
            if (llmProvider == null) {
                throw new IllegalStateException("llmProvider is required when query rewriting is enabled");
            }
            ragQuery = DefaultRagQueryRewriteAgent.of(llmProvider).run(query);
        }

        // Step 2: Generate embedding for the query
        var embeddingResponse = llmProvider.embeddings(new EmbeddingRequest(List.of(ragQuery)));
        var embedding = embeddingResponse.embeddings.getFirst().embedding;

        // Step 3: Similarity search in vector store
        var searchRequest = SimilaritySearchRequest.builder()
            .embedding(embedding)
            .threshold(threshold)
            .topK(topK)
            .build();

        List<Document> documents = vectorStore.similaritySearch(searchRequest);

        if (documents.isEmpty()) {
            return "No relevant documents found for the query: " + query;
        }

        // Step 4: Rerank documents for better relevance
        List<String> contents = documents.stream()
            .map(doc -> doc.content)
            .collect(Collectors.toList());

        var rerankingResponse = llmProvider.rerankings(RerankingRequest.of(ragQuery, contents));

        if (rerankingResponse.rerankedDocuments.isEmpty()) {
            return "No relevant context found after reranking";
        }

        // Return the most relevant document
        String topContext = rerankingResponse.rerankedDocuments.getFirst();

        // Format the response
        return String.format("Retrieved Context:\n%s", topContext);
    }

    @Override
    public List<ToolCallParameter> getParameters() {
        return List.of(
            ToolCallParameter.builder()
                .name("query")
                .description("The search query to find relevant documents")
                .type(ToolCallParameterType.STRING)
                .classType(String.class)
                .required(true)
                .build(),
            ToolCallParameter.builder()
                .name("topK")
                .description("Number of top similar documents to retrieve (default: 5)")
                .type(ToolCallParameterType.INTEGER)
                .classType(Integer.class)
                .required(false)
                .build(),
            ToolCallParameter.builder()
                .name("threshold")
                .description("Minimum similarity threshold for document retrieval (default: 0.0)")
                .type(ToolCallParameterType.DOUBLE)
                .classType(Double.class)
                .required(false)
                .build()
        );
    }

    public static class Builder extends ToolCall.Builder<Builder, RagTool> {
        private VectorStore vectorStore;
        private LLMProvider llmProvider;
        private RagConfig ragConfig;
        private Integer defaultTopK = 5;
        private Double defaultThreshold = 0d;
        private Boolean enableQueryRewriting = true;  // Default enabled

        @Override
        protected Builder self() {
            return this;
        }

        public Builder vectorStore(VectorStore vectorStore) {
            this.vectorStore = vectorStore;
            return this;
        }

        public Builder llmProvider(LLMProvider llmProvider) {
            this.llmProvider = llmProvider;
            return this;
        }

        public Builder ragConfig(RagConfig ragConfig) {
            this.ragConfig = ragConfig;
            return this;
        }

        public Builder defaultTopK(Integer topK) {
            this.defaultTopK = topK;
            return this;
        }

        public Builder defaultThreshold(Double threshold) {
            this.defaultThreshold = threshold;
            return this;
        }

        public Builder enableQueryRewriting(Boolean enable) {
            this.enableQueryRewriting = enable;
            return this;
        }

        public RagTool build() {
            RagTool ragTool;
            if (ragConfig != null) {
                ragTool = new RagTool(ragConfig);
            } else {
                ragTool = new RagTool(vectorStore, llmProvider);
            }

            // Try to build with current settings
            // If name or description is missing, set both defaults and retry
            // We set both because we can't be sure which one triggered the exception
            try {
                super.build(ragTool);
            } catch (RuntimeException e) {
                if (e.getMessage() != null
                    && (e.getMessage().contains("name is required")
                        || e.getMessage().contains("description is required"))) {
                    // Set both defaults to ensure they're both present
                    name("rag_search");
                    description("Search for relevant documents using RAG (Retrieval-Augmented Generation)");
                    // Retry build with defaults set
                    super.build(ragTool);
                } else {
                    throw e;
                }
            }

            // Set RagTool-specific fields
            if (defaultTopK != null) {
                ragTool.defaultTopK = defaultTopK;
            }
            if (defaultThreshold != null) {
                ragTool.defaultThreshold = defaultThreshold;
            }
            if (enableQueryRewriting != null) {
                ragTool.enableQueryRewriting = enableQueryRewriting;
            }

            return ragTool;
        }
    }
}