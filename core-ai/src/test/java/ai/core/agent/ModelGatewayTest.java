package ai.core.agent;

import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ReasoningEffort;
import ai.core.llm.domain.RoleType;
import ai.core.llm.providers.MockLLMProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelGatewayTest {
    @Test
    void captionModelDoesNotReplaceTheMainLoopModel() {
        var agent = Agent.builder()
                .name("text-main")
                .systemPrompt("test")
                .llmProvider(new MockLLMProvider())
                .model("deepseek-model")
                .multiModalModel("vision-model")
                .compression(false)
                .build();
        assertEquals("deepseek-model", ModelGateway.resolveEffectiveModel(agent));
    }

    @Test
    void imageHistoryKeepsMainModelAndReasoningOnTheActualRequest() {
        var provider = new MockLLMProvider();
        provider.addResponse(CompletionResponse.of(
                List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "ok"))),
                null));
        var captured = new AtomicReference<CompletionRequest>();
        var agent = Agent.builder()
                .name("text-main")
                .systemPrompt("test")
                .llmProvider(provider)
                .model("deepseek-model")
                .multiModalModel("vision-model")
                .reasoningEffort(ReasoningEffort.HIGH)
                .addAgentLifecycle(new AbstractLifecycle() {
                    @Override
                    public void beforeModel(CompletionRequest request, ExecutionContext context) {
                        captured.set(request);
                    }
                })
                .compression(false)
                .build();
        var imageMessage = Message.of(new Message.MessageRecord(
                RoleType.USER,
                List.of(
                        Content.of("describe this image"),
                        Content.of(Content.ImageUrl.of("https://blob.example/image.png", "image/png"))),
                null, null, null, null));

        ModelGateway.handLLM(agent, List.of(imageMessage), List.of());

        assertEquals("deepseek-model", captured.get().model);
        assertEquals(ReasoningEffort.HIGH, captured.get().reasoningEffort);
    }
}
