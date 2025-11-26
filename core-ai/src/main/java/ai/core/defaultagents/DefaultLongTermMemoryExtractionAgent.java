package ai.core.defaultagents;

import ai.core.agent.Agent;
import ai.core.agent.formatter.formatters.DefaultJsonFormatter;
import ai.core.llm.LLMProvider;
import core.framework.api.json.Property;
import core.framework.json.JSON;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class DefaultLongTermMemoryExtractionAgent {
    private final LLMProvider llmProvider;

    public DefaultLongTermMemoryExtractionAgent(LLMProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

    Agent of(LLMProvider llmProvider) {
        return Agent.builder()
                .name("long-term-memory-extraction-agent")
                .description("A default agent for extracting long-term memory from conversation history using LLMs.")
                .systemPrompt("""
                        # ROLE
                        You are a "Memory Extraction Specialist" AI. Your sole purpose is to analyze a conversation and extract key, long-term memories about the user.
                        
                        # INSTRUCTIONS
                        1.  Carefully analyze the `CONVERSATION` provided below.
                        2.  Extract pieces of information that are significant for long-term memory. Focus on:
                            -   **Facts**: Specific, objective information about the user (e.g., "User's name is Alex", "User lives in Paris", "User's dog is named Fido").
                            -   **Preferences**: The user's likes and dislikes (e.g., "User is a vegetarian", "User prefers tea over coffee", "User dislikes horror movies").
                            -   **Goals/Intentions**: The user's long-term plans or objectives (e.g., "User is learning to play the guitar", "User is planning a trip to Japan next year").
                        3.  You MUST IGNORE trivial information, small talk, pleasantries, questions directed at the AI, and purely temporary context (e.g., "what is the weather like *today*", "I'm busy *this afternoon*").
                        4.  You MUST format the extracted memories as a single JSON object containing a single key named "memories".
                        5.  The value of the "memories" key MUST be a list of strings. Each string in the list should be a concise, self-contained memory.
                        6.  **If NO significant memories are found in the conversation, you MUST return a JSON object with an empty list. Example: `{"memories": []}`.**
                        
                        # CONVERSATION
                        {conversation_text}
                        
                        # JSON OUTPUT
                        """)
                .formatter(new DefaultJsonFormatter(true))
                .llmProvider(llmProvider).build();
    }

    public List<String> extractMemories(String conversationText) {
        var agent = of(llmProvider);
        var response = agent.run(conversationText, (Map<String, Object>) null);
        var dto = JSON.fromJSON(longTermMemoryDto.class, response);
        return dto.memories;
    }

    public static class longTermMemoryDto {
        @Property(name = "memories")
        public List<String> memories;
    }
}
