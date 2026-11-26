package ai.core.telemetry;

import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.utils.JsonUtil;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        var completion = CompletionResponse.of(List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "ok"))),
            new Usage(10, 20, 30));
        completion.ttftMs = 1234L;
        completion.firstContentMs = 2345L;
        var details = new Usage.CompletionTokensDetails();
        details.reasoningTokens = 15;
        completion.usage.setCompletionTokensDetails(details);

        tracer.traceLLMCompletion("litellm", request, () -> completion);

        var span = processor.find("test").orElseThrow();
        assertEquals(1234L, span.getAttributes().get(AttributeKey.longKey("gen_ai.response.time_to_first_token_ms")));
        assertEquals(2345L, span.getAttributes().get(AttributeKey.longKey("gen_ai.response.first_content_ms")));
        assertEquals(15L, span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.reasoning_tokens")));
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

    @Test
    void oversizedInlineImagesNeverReachTheSpan() {
        var processor = new RecordingSpanProcessor();
        var tracerProvider = SdkTracerProvider.builder().addSpanProcessor(processor).build();
        var tracer = new LLMTracer(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build(), true);
        var image = Content.of(Content.ImageUrl.of("data:image/png;base64," + "A".repeat(2_000_000), "image/png"));
        var request = CompletionRequest.of(List.of(Message.of(new Message.MessageRecord(RoleType.USER,
                List.of(Content.of("look"), image), null, null, null, null))), null, null, "test-model", "test");
        var completion = CompletionResponse.of(List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "ok"))), null);

        assertSame(completion, tracer.traceLLMCompletion("litellm", request, () -> completion));

        var span = processor.find("test").orElseThrow();
        var input = span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input"));
        assertFalse(input.contains("data:image"));
        assertTrue(input.length() < 1024, "the placeholder should be tiny, input=" + input);
        assertNull(span.getAttributes().get(AttributeKey.booleanKey("coreai.trace.input_truncated")));
        tracerProvider.close();
    }

    @Test
    void capsTheSerializedInputOfALargeRequest() {
        var processor = new RecordingSpanProcessor();
        var tracerProvider = SdkTracerProvider.builder().addSpanProcessor(processor).build();
        var tracer = new LLMTracer(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build(), true);
        var messages = new ArrayList<Message>();
        for (var i = 0; i < 20; i++) {
            messages.add(Message.of(RoleType.USER, "x".repeat(100_000)));
        }
        var request = CompletionRequest.of(messages, null, null, "test-model", "test");
        var completion = CompletionResponse.of(List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "ok"))), null);

        tracer.traceLLMCompletion("litellm", request, () -> completion);

        var span = processor.find("test").orElseThrow();
        var input = span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input"));
        assertTrue(input.getBytes(StandardCharsets.UTF_8).length <= 256 * 1024,
                "span input must stay within the cap, bytes=" + input.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(Boolean.TRUE, span.getAttributes().get(AttributeKey.booleanKey("coreai.trace.input_truncated")));
        assertDoesNotThrow(() -> JsonUtil.OBJECT_MAPPER.readTree(input));
        tracerProvider.close();
    }

    @Test
    void keepsSmallInlineImagesForCompatibility() {
        var processor = new RecordingSpanProcessor();
        var tracerProvider = SdkTracerProvider.builder().addSpanProcessor(processor).build();
        var tracer = new LLMTracer(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build(), true);
        var image = Content.of(Content.ImageUrl.of("data:image/png;base64," + "A".repeat(4096), "image/png"));
        var request = CompletionRequest.of(List.of(Message.of(new Message.MessageRecord(RoleType.USER,
                List.of(Content.of("look"), image), null, null, null, null))), null, null, "test-model", "test");
        var completion = CompletionResponse.of(List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "ok"))), null);

        tracer.traceLLMCompletion("litellm", request, () -> completion);

        var input = processor.find("test").orElseThrow().getAttributes()
                .get(AttributeKey.stringKey("langfuse.observation.input"));
        assertTrue(input.contains("data:image"));
        tracerProvider.close();
    }
}
