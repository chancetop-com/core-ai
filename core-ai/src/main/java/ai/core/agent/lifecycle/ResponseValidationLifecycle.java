package ai.core.agent.lifecycle;

import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.prompt.Prompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Validates LLM responses and retries with a reminder when the response is invalid.
 * Currently handles empty content responses.
 */
public class ResponseValidationLifecycle extends AbstractLifecycle {
    private final Logger logger = LoggerFactory.getLogger(ResponseValidationLifecycle.class);

    @Override
    public List<Message> onModelResponse(CompletionRequest request, CompletionResponse response, ExecutionContext context) {
        var choice = response.choices.getFirst();
        var hasToolCalls = choice.message.toolCalls != null && !choice.message.toolCalls.isEmpty();
        if (hasToolCalls) return null;

        var content = choice.message.content;
        if (content == null || content.isBlank()) {
            logger.warn("LLM returned empty content, injecting reminder");
            return List.of(
                    choice.message.toMessage(),
                    Message.of(RoleType.USER, Prompts.EMPTY_RESPONSE_REMINDER, "reminder", null, null)
            );
        }

        return null;
    }
}
