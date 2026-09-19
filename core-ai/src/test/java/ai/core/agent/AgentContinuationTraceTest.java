package ai.core.agent;

import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.MockLLMProvider;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.telemetry.AgentTracer;
import ai.core.telemetry.RecordingSpanProcessor;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @author stephen
 */
class AgentContinuationTraceTest {
    private static final AttributeKey<String> SESSION_ID = AttributeKey.stringKey("session.id");
    private static final AttributeKey<String> GEN_AI_AGENT_NAME = AttributeKey.stringKey("gen_ai.agent.name");
    private static final AttributeKey<String> GEN_AI_PROMPT = AttributeKey.stringKey("gen_ai.prompt");

    @Test
    void injectedContinuationIsTracedAsAgentTurnCarryingSession() {
        var spans = new RecordingSpanProcessor();
        var tracerProvider = SdkTracerProvider.builder().addSpanProcessor(spans).build();
        try {
            var openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
            var provider = new ContinuationProvider();
            provider.addResponse(CompletionResponse.of(
                    List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "reviewed the batch"))),
                    new Usage(10, 10, 20)));
            var agent = Agent.builder()
                    .name("director")
                    .systemPrompt("You are a director")
                    .llmProvider(provider)
                    .tracer(new AgentTracer(openTelemetry, true))
                    .build();
            agent.setExecutionContext(ExecutionContext.builder().sessionId("session-1").userId("user-1").build());

            agent.injectUserMessage("<task-notification taskId=\"t1\"/>");
            var output = agent.continueWithInjectedMessage();

            assertEquals("reviewed the batch", output);
            var agentTurn = spans.find("agent.turn").orElseThrow();
            assertEquals("session-1", agentTurn.getAttributes().get(SESSION_ID));
            assertEquals("director", agentTurn.getAttributes().get(GEN_AI_AGENT_NAME));
            assertEquals("<task-notification taskId=\"t1\"/>", agentTurn.getAttributes().get(GEN_AI_PROMPT));
            assertNotNull(provider.spanDuringCall);
            assertEquals(agentTurn.getSpanId(), provider.spanDuringCall.getSpanId());
            assertEquals(agentTurn.getTraceId(), provider.spanDuringCall.getTraceId());
        } finally {
            tracerProvider.shutdown();
        }
    }

    private static final class ContinuationProvider extends MockLLMProvider {
        private SpanContext spanDuringCall;

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            spanDuringCall = Span.current().getSpanContext();
            return super.doCompletionStream(request, callback);
        }
    }
}
