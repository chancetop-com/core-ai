package ai.core.example.socialmedia.agent;

import ai.core.agent.Agent;
import ai.core.rag.RagConfig;
import ai.core.llm.LLMProvider;
import ai.core.rag.VectorStore;

/**
 * @author stephen
 */
public class WonderExpertAgent {
    private final Agent agent;

    public WonderExpertAgent(LLMProvider llmProvider, VectorStore vectorStore) {
        this.agent = Agent.builder()
                .name("wonder-expert-agent")
                .description("An agent that using RAG to fetch Wonder knowledge")
                .systemPrompt("Your are an expert of Wonder.Pickup the Wonder information from the Context, and return 'I don't know that yet.' if you don't find answer from the Context.")
                .llmProvider(llmProvider)
                .ragConfig(RagConfig.builder()
                        .useRag(Boolean.TRUE)
                        .threshold(0.8)
                        .collection("wiki")
                        .vectorStore(vectorStore).build()).build();
    }

    public String run(String query) {
        return agent.run(query, null);
    }
}
