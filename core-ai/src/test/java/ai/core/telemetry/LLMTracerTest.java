package ai.core.telemetry;

import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LLMTracerTest {
    @Test
    void traceLLMCompletionRejectsNullProviderResponseWhenNotCancelled() {
        var tracer = new LLMTracer(OpenTelemetry.noop(), true);
        var request = CompletionRequest.of(List.of(Message.of(RoleType.USER, "hi")), null, null, "test-model", "test");

        assertThrows(IllegalStateException.class, () -> tracer.traceLLMCompletion("litellm", request, () -> null));
    }

    @Test
    void traceLLMCompletionAllowsNullProviderResponseWhenCancelled() {
        var tracer = new LLMTracer(OpenTelemetry.noop(), true);
        var request = CompletionRequest.of(List.of(Message.of(RoleType.USER, "hi")), null, null, "test-model", "test");

        var response = tracer.traceLLMCompletion("litellm", request, () -> null, null, () -> true);

        assertNull(response);
    }

    @Test
    void traceLLMCompletionAllowsMissingUsage() {
        var tracer = new LLMTracer(OpenTelemetry.noop(), true);
        var request = CompletionRequest.of(List.of(Message.of(RoleType.USER, "hi")), null, null, "test-model", "test");
        var completion = CompletionResponse.of(List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "ok"))), null);

        var response = tracer.traceLLMCompletion("litellm", request, () -> completion);

        assertSame(completion, response);
    }

    @Test
    void recordsTtftOnTheLlmSpan() {
        var processor = new RecordingSpanProcessor();
        var tracerProvider = SdkTracerProvider.builder().addSpanProcessor(processor).build();
        var openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        var tracer = new LLMTracer(openTelemetry, true);
        var request = CompletionRequest.of(List.of(Message.of(RoleType.USER, "hi")), null, null, "test-model", "test");
        var completion = CompletionResponse.of(List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "ok"))), null);
        completion.ttftMs = 1234L;

        tracer.traceLLMCompletion("litellm", request, () -> completion);

        var span = processor.find("test").orElseThrow();
        assertEquals(1234L, span.getAttributes().get(AttributeKey.longKey("gen_ai.response.time_to_first_token_ms")));
        tracerProvider.close();
    }

    @Test
    void omitsTtftWhenTheCallNeverProducedAToken() {
        var processor = new RecordingSpanProcessor();
        var tracerProvider = SdkTracerProvider.builder().addSpanProcessor(processor).build();
        var openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        var tracer = new LLMTracer(openTelemetry, true);
        var request = CompletionRequest.of(List.of(Message.of(RoleType.USER, "hi")), null, null, "test-model", "test");
        var completion = CompletionResponse.of(List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "ok"))), null);

        tracer.traceLLMCompletion("litellm", request, () -> completion);

        var span = processor.find("test").orElseThrow();
        assertNull(span.getAttributes().get(AttributeKey.longKey("gen_ai.response.time_to_first_token_ms")));
        tracerProvider.close();
    }
}
