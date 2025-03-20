package ai.core.defaultagents;

import ai.core.agent.Agent;
import ai.core.llm.LLMProvider;

import java.util.Map;

/**
 * @author stephen
 */
public class DefaultAnswerRetrievalAgent {
    public static Agent of(LLMProvider llmProvider) {
        return Agent.builder()
                .name("answer-retrieval-agent")
                .description("This agent is retrieval answer from information that provided by user.")
                .systemPrompt("""
                        You are an assistant to help users retrieval answer from information that provided by user.
                        You need to read through the question and information provided by the user and output your answer.
                        """)
                .promptTemplate("""
                        question:
                        {{question}}
                        information:
                        {{information}}
                        """)
                .llmProvider(llmProvider).build();
    }

    public static Map<String, Object> buildContext(String question, String query) {
        return Map.of(
                "question", question,
                "information", query
        );
    }
}
