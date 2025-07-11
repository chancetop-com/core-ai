package ai.core.rag;

import ai.core.defaultagents.DefaultAnswerRetrievalAgent;
import ai.core.defaultagents.DefaultSummaryAgent;
import ai.core.document.Document;
import ai.core.document.TextChunk;
import ai.core.document.TextSplitter;
import ai.core.document.textsplitters.RecursiveCharacterTextSplitter;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.Usage;
import ai.core.vectorstore.VectorStore;
import core.framework.util.Strings;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class LongQueryHandler {
    private final TextSplitter textSplitter;
    private final LLMProvider llmProvider;
    private final LongQueryHandlerType type;
    private final VectorStore vectorStore;

    public LongQueryHandler(LLMProvider llmProvider, LongQueryHandlerType type, VectorStore vectorStore, TextSplitter textSplitter) {
        this.llmProvider = llmProvider;
        this.type = type;
        this.vectorStore = vectorStore;
        this.textSplitter = textSplitter;
    }

    public LongQueryHandlerResult handler(String question, String query) {
        return switch (type) {
            case SUMMARY -> longQuerySummary(query);
            case RAG -> longQueryRag(question, query, textSplitter);
            case AGENT -> longQueryAgent(question, query);
        };
    }

    private LongQueryHandlerResult longQueryAgent(String question, String query) {
        if (query.length() > llmProvider.maxTokens()) {
            return longQueryRag(question, query, new RecursiveCharacterTextSplitter());
        }
        var agent = DefaultAnswerRetrievalAgent.of(llmProvider);
        var rst = agent.run("", DefaultAnswerRetrievalAgent.buildContext(question, query));
        return new LongQueryHandlerResult(rst, agent.getCurrentTokenUsage());
    }

    private LongQueryHandlerResult longQuerySummary(String query) {
        var agent = DefaultSummaryAgent.of(llmProvider);
        var rst = "The output is too long for LLM, please re-planning the agent if the output summary is not enough, re-planning for example: adjust query, adjust tool parameters or use another tool, the output summary: \n"
                + agent.run(truncateQuery(query), null);
        return new LongQueryHandlerResult(rst, agent.getCurrentTokenUsage());
    }

    private String truncateQuery(String query) {
        return query.substring(0, Math.min(query.length(), llmProvider.maxTokens()));
    }

    private LongQueryHandlerResult longQueryRag(String question, String query, TextSplitter textSplitter) {
        // to question cannot rag
        if (Strings.isBlank(query) && Strings.isBlank(question)) return new LongQueryHandlerResult(query, new Usage());
        var embeddingTexts = textSplitter.split(query).stream().map(TextChunk::embeddingText).collect(Collectors.toList());
        var documents = vectorStore.getAll(embeddingTexts);
        embeddingTexts.removeAll(documents.stream().map(v -> v.content).toList());
        var usage = new Usage();
        if (!embeddingTexts.isEmpty()) {
            var rsp = llmProvider.embeddings(new EmbeddingRequest(embeddingTexts));
            var missingDocuments = rsp.embeddings.stream().map(v -> new Document(v.text, v.embedding, null)).toList();
            vectorStore.add(missingDocuments);
            usage.add(rsp.usage);
        }
        var rsp = llmProvider.embeddings(new EmbeddingRequest(List.of(question)));
        var text = vectorStore.similaritySearchText(SimilaritySearchRequest.builder().topK(1).embedding(rsp.embeddings.getFirst().embedding).build());
        usage.add(rsp.usage);
        return new LongQueryHandlerResult(text, rsp.usage);
    }
}
