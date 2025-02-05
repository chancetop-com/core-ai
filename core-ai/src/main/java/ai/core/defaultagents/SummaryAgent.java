package ai.core.defaultagents;

import ai.core.agent.Agent;
import ai.core.agent.AgentChain;
import ai.core.llm.LLMProvider;

/**
 * @author stephen
 */
public class SummaryAgent {

    public static Agent of(LLMProvider llmProvider) {
        return Agent.builder()
                .name("summary-agent")
                .systemPrompt("You are a helpful AI Assistant to help summarize from the conversation. Do not add any introductory phrases. Keep the summary results to fewer than 10 words.")
                .promptTemplate("conversation: ")
                .llmProvider(llmProvider).build();
    }

    public static String summaryTopic(AgentChain agentChain, Agent agent) {
        return agent.run(agentChain.getConversationText(), null);
    }
}
