package ai.core.example.service;

import ai.core.agent.Agent;
import ai.core.rag.RagConfig;
import ai.core.llm.providers.LiteLLMProvider;
import ai.core.rag.vectorstore.milvus.MilvusVectorStore;
import core.framework.inject.Inject;

public class ChatAgent {
    private Agent agent;
    @Inject
    LiteLLMProvider llmProvider;
    @Inject
    MilvusVectorStore vectorStore;

    public void init() {
        if (agent != null) {
            return;
        }
        agent = Agent.builder()
                .name("wonder-chat-agent")
                .description("An agent that using RAG to fetch Wonder knowledge")
                .systemPrompt("Hello, I'm Wonder Chat Agent. How can I help you?")
                .llmProvider(llmProvider)
                .ragConfig(RagConfig.builder()
                        .useRag(Boolean.TRUE)
                        .threshold(0.8)
                        .collection("wiki")
                        .vectorStore(vectorStore).build()).build();
        agent.addLongTernMemory("Chelsea's wall is green");
    }

    public String run(String query) {
        if (agent == null) {
            init();
        }
        return agent.run(query, null);
    }
}
