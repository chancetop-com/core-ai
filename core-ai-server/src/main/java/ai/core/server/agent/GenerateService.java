package ai.core.server.agent;

import ai.core.api.server.utils.GenerateRequest;
import ai.core.api.server.utils.GenerateResponse;
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
public class GenerateService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GenerateService.class);

    @Inject
    LLMProviders llmProviders;

    public GenerateResponse generate(GenerateRequest request) {
        LOGGER.info("generating with system prompt, length={}, user prompt length={}",
                request.systemPrompt != null ? request.systemPrompt.length() : 0,
                request.userPrompt != null ? request.userPrompt.length() : 0);

        var messages = List.of(
                Message.of(RoleType.SYSTEM, request.systemPrompt != null ? request.systemPrompt : ""),
                Message.of(RoleType.USER, request.userPrompt != null ? request.userPrompt : "")
        );

        var completionRequest = CompletionRequest.of(messages, null, null, null, null);
        var completionResponse = llmProviders.getDefaultProvider().completion(completionRequest);

        var content = completionResponse.choices.getFirst().message.content;
        LOGGER.info("generated output, length={}", content != null ? content.length() : 0);

        var response = new GenerateResponse();
        response.output = content;
        return response;
    }
}
