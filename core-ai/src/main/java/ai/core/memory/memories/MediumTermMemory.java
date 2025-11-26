package ai.core.memory.memories;

import ai.core.document.Document;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.memory.Memory;
import ai.core.vectorstore.VectorStore;
import ai.core.rag.SimilaritySearchRequest;

import java.util.List;
import java.util.stream.Collectors;

public class MediumTermMemory extends Memory {
    private final VectorStore vectorStore;
    private final LLMProvider llmProvider;
    private static final String SUMMARY_PROMPT = "Please summarize the following conversation into a concise paragraph, focusing on the key topics and decisions made:\n";

    public MediumTermMemory(VectorStore vectorStore, LLMProvider llmProvider) {
        this.vectorStore = vectorStore;
        this.llmProvider = llmProvider;
    }

    @Override
    public void extractAndSave(List<Message> conversation) {
        if (conversation == null || conversation.isEmpty()) return;

        // 1. Convert conversation to text
        String conversationText = conversation.stream()
                .map(msg -> msg.role + ": " + msg.content)
                .collect(Collectors.joining("\n"));

        // 2. Summarize using LLM (keep this for quick semantic search)
        String summary = summarize(conversationText);

        // 3. Embed and save BOTH summary and full text
        // We save the full text as the content, and maybe the summary as metadata or just another doc
        // For now, let's save the full text, as the user wants "Full conversation records"
        // And we can also save the summary as a separate document for better semantic retrieval
        
        add(conversationText); // Save full log
        add("Summary: " + summary); // Save summary
    }

    private String summarize(String text) {
        var request = CompletionRequest.of(List.of(Message.of(RoleType.USER, SUMMARY_PROMPT + text)),null,null,null,null);
        var response = llmProvider.completion(request);
        return response.choices.getFirst().message.content;
    }

    @Override
    public List<Document> retrieve(String query) {
        // Embed query
        var embeddingResponse = llmProvider.embeddings(new EmbeddingRequest(List.of(query)));
        var embedding = embeddingResponse.embeddings.getFirst().embedding;

        // Search vector store
        // If the VectorStore supports keyword search, it would be ideal here.
        // Assuming similaritySearch handles the retrieval.
        return vectorStore.similaritySearch(SimilaritySearchRequest.builder()
                .embedding(embedding)
                .topK(5) // Retrieve top 5 relevant summaries/logs
                .threshold(0.7)
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
        // VectorStore might not support clear easily, or we might not want to clear it often
        // For now, no-op or throw unsupported
    }

    @Override
    public List<Document> list() {
        // VectorStore might not support listing all
        return List.of();
    }
}
