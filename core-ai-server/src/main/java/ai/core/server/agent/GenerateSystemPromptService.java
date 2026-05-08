package ai.core.server.agent;

import ai.core.api.server.agent.GenerateSystemPromptRequest;
import ai.core.api.server.agent.GenerateSystemPromptResponse;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author stephen
 */
public class GenerateSystemPromptService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GenerateSystemPromptService.class);

    private static final String SYSTEM_PROMPT = """
            You are a system prompt generator for AI agents. Your task is to generate effective, well-structured system prompts based on the agent's name and description.

            Generate a system prompt that:
            - Defines the agent's role and personality clearly
            - Specifies how the agent should behave and respond
            - Includes any relevant constraints or guidelines
            - Uses clear, actionable language
            - Is concise but comprehensive

            Return ONLY the system prompt text without any additional commentary, markdown formatting, or labels.
            """;

    @Inject
    LLMProviders llmProviders;

    public GenerateSystemPromptResponse generate(GenerateSystemPromptRequest request) {
        LOGGER.info("generating system prompt for agent, name={}, description={}", request.name, request.description);

        var userPrompt = buildUserPrompt(request.name, request.description);

        var completionRequest = CompletionRequest.of(
                List.of(Message.of(RoleType.SYSTEM, SYSTEM_PROMPT), Message.of(RoleType.USER, userPrompt)),
                null,
                null,
                null,
                null
        );
        var completionResponse = llmProviders.getDefaultProvider().completion(completionRequest);

        var content = completionResponse.choices.getFirst().message.content;
        LOGGER.info("generated system prompt, length={}", content != null ? content.length() : 0);

        var response = new GenerateSystemPromptResponse();
        response.systemPrompt = content;
        return response;
    }

    private String buildUserPrompt(String name, String description) {
        var sb = new StringBuilder();
        sb.append("Generate a system prompt for an AI agent with the following details:\n\n");
        sb.append("Name: ").append(name != null ? name : "N/A").append("\n");
        sb.append("Description: ").append(description != null ? description : "N/A").append("\n\n");
        sb.append("The system prompt should define how this agent behaves, its capabilities, and its constraints.");
        return sb.toString();
    }
}
