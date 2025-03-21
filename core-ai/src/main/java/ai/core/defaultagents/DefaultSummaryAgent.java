package ai.core.defaultagents;

import ai.core.agent.Agent;
import ai.core.agent.AgentChain;
import ai.core.agent.Node;
import ai.core.llm.LLMProvider;

/**
 * @author stephen
 */
public class DefaultSummaryAgent {

    public static Agent of(LLMProvider llmProvider) {
        return of(llmProvider, null);
    }

    public static Agent of(LLMProvider llmProvider, Node<?> node) {
        return Agent.builder()
                .name("summary-agent")
                .systemPrompt("""
                You are a helpful AI Assistant to help summarize from the text.
                Summarize the entire text, list the key information, remove what you consider to be redundant information, and keep the output within 5-10 sentences.
                Do not add any introductory phrases.
                """)
                .promptTemplate("Query: ")
                .parent(node)
                .llmProvider(llmProvider).build();
    }

    public static String summaryTopic(AgentChain agentChain, Agent agent) {
        return agent.run(agentChain.getConversationText(), null);
    }
}
