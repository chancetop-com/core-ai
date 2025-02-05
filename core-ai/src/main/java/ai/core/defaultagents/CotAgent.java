package ai.core.defaultagents;

import ai.core.agent.Agent;
import ai.core.agent.AgentRole;
import ai.core.agent.NodeStatus;
import ai.core.agent.formatter.formatters.DefaultJsonFormatter;
import ai.core.agent.listener.listeners.DefaultAgentMessageEventListener;
import ai.core.agent.listener.listeners.DefaultAgentRunningEventListener;
import ai.core.defaultagents.inner.CotTermination;
import ai.core.llm.LLMProvider;
import ai.core.reflection.Reflection;
import ai.core.reflection.ReflectionConfig;
import core.framework.api.json.Property;
import core.framework.json.JSON;
import core.framework.util.Strings;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class CotAgent {
    public static Agent of(LLMProvider llmProvider) {
        return Agent.builder()
                .name("cot-agent")
                .description("Assistant agent that have COT(Chain Of Thought) capability.")
                .systemPrompt("""
                        You are an AI assistant that explains your reasoning step by step, incorporating dynamic Chain of Thought (CoT), reflection, and verbal reinforcement learning. Follow these instructions:
                        
                        1. Enclose all thoughts within <thinking> tags, exploring multiple angles and approaches.
                        2. Break down the solution into clear steps, providing a title and content for each step.
                        3. After each step, decide if you need another step or if you're ready to give the final answer.
                        4. Continuously adjust your reasoning based on intermediate results and reflections, adapting your strategy as you progress.
                        5. Regularly evaluate your progress, being critical and honest about your reasoning process.
                        6. Assign a quality score between 0.0 and 1.0 to guide your approach:
                           - 0.8+: Continue current approach
                           - 0.5-0.7: Consider minor adjustments
                           - Below 0.5: Seriously consider backtracking and trying a different approach
                        7. If unsure or if your score is low, backtrack and try a different approach, explaining your decision.
                        8. For mathematical problems, show all work explicitly using LaTeX for formal notation and provide detailed proofs.
                        9. Explore multiple solutions individually if possible, comparing approaches in your reflections.
                        10. Use your thoughts as a scratchpad, writing out all calculations and reasoning explicitly.
                        11. Use at least 5 methods to derive the answer and consider alternative viewpoints.
                        12. Be aware of your limitations as an AI and what you can and cannot do.
                        
                        After every 3 steps, perform a detailed self-reflection on your reasoning so far, considering potential biases and alternative viewpoints.
                        
                        Respond in JSON format with 'title', 'answer', 'next_action' (either 'continue', or 'terminate'), and 'confidence' (a number between 0 and 1) keys.
                        
                        Example of a valid JSON response:
                        ```json
                        {
                            "steps":
                            [
                                {
                                    "title": "Identifying Key Information",
                                    "answer": "To begin solving this problem, we need to carefully examine the given information and identify the crucial elements that will guide our solution process. This involves...",
                                    "next_action": "continue",
                                    "confidence": 0.8
                                }
                            ]
                        }```
                        
                        Your goal is to demonstrate a thorough, adaptive, and self-reflective problem-solving process, emphasizing dynamic thinking and learning from your own reasoning.
                        """)
                .promptTemplate("conversation: ")
                .reflectionConfig(new ReflectionConfig(true, 2, 1, Reflection.DEFAULT_REFLECTION_CONTINUE_TEMPLATE))
                .formatter(new DefaultJsonFormatter())
                .terminations(List.of(new CotTermination()))
                .messageUpdatedEventListener(new DefaultAgentMessageEventListener())
                .statusChangedEventListeners(Map.of(NodeStatus.RUNNING, new DefaultAgentRunningEventListener()))
                .llmProvider(llmProvider).build();
    }

    public static List<String> getConversationText(Agent agent) {
        return agent.getMessages().stream().filter(t -> t.role == AgentRole.ASSISTANT).map(v -> JSON.fromJSON(CotResult.class, v.content)).flatMap(d -> d.steps.stream()).map(s -> s.answer + Strings.format(" (confident: {})", s.confidence)).toList();
    }
    public static class CotResult {
        @Property(name = "steps")
        public List<Step> steps;

        public enum Action {
            @Property(name = "terminate")
            TERMINATE,
            @Property(name = "continue")
            CONTINUE
        }

        public static class Step {
            @Property(name = "next_action")
            public Action nextAction;

            @Property(name = "confidence")
            public Double confidence;

            @Property(name = "title")
            public String title;

            @Property(name = "answer")
            public String answer;
        }
    }
}
