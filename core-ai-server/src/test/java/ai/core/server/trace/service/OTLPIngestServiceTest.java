package ai.core.server.trace.service;

import ai.core.server.domain.AgentRun;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.Trace;
import com.google.protobuf.ByteString;
import core.framework.mongo.MongoCollection;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OTLPIngestServiceTest {
    @Test
    void calculatesCostFromGatewayModelPrice() {
        var service = service();
        var model = new GatewayModelConfig();
        model.modelId = "gpt-5.6-terra";
        model.inputPricePer1MTokens = 2D;
        model.outputPricePer1MTokens = 8D;
        when(service.modelPricingService.gatewayModelCollection.find(any(Bson.class))).thenReturn(List.of(model));
        when(service.traceCollection.find(any(Bson.class))).thenReturn(List.of()).thenReturn(List.of(new Trace()));

        service.ingest(request(span("chat",
            attr("gen_ai.request.model", "gpt-5.6-terra"),
            attr("gen_ai.usage.input_tokens", "1000000"),
            attr("gen_ai.usage.output_tokens", "500000"))));

        var inserted = ArgumentCaptor.forClass(Span.class);
        verify(service.spanCollection).insert(inserted.capture());
        assertEquals(6D, inserted.getValue().costUsd);
        assertEquals("gateway_model", inserted.getValue().costSource);
        assertEquals("gpt-5.6-terra", inserted.getValue().pricingModelId);
    }

    @Test
    void rootOperationSpanUsesAgentNameForTraceTitle() {
        var service = service();
        when(service.traceCollection.find(any(Bson.class))).thenReturn(List.of()).thenReturn(List.of(new Trace()));
        when(service.spanCollection.find(any(Bson.class))).thenReturn(List.of());

        service.ingest(request(span("agent.run",
            attr("langfuse.observation.type", "agent"),
            attr("gen_ai.agent.name", "Xander-test (1)"),
            attr("gen_ai.agent.id", "agent-123"))));

        var inserted = ArgumentCaptor.forClass(Trace.class);
        verify(service.traceCollection).insert(inserted.capture());
        assertEquals("Xander-test (1)", inserted.getValue().name);
        assertEquals("Xander-test (1)", inserted.getValue().agentName);
    }

    @Test
    void stripsDuplicatedPayloadAttributesFromStoredSpan() {
        var service = service();
        when(service.traceCollection.find(any(Bson.class))).thenReturn(List.of()).thenReturn(List.of(new Trace()));
        when(service.spanCollection.find(any(Bson.class))).thenReturn(List.of());

        var input = "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}";
        var output = "{\"role\":\"assistant\",\"content\":\"hi\"}";
        service.ingest(request(span("agent.run",
            attr("langfuse.observation.type", "agent"),
            attr("langfuse.observation.input", input),
            attr("langfuse.observation.output", output),
            attr("gen_ai.prompt", input),
            attr("gen_ai.completion", output),
            attr("gen_ai.usage.input_tokens", "10"),
            attr("gen_ai.usage.output_tokens", "5"))));

        var inserted = ArgumentCaptor.forClass(Span.class);
        verify(service.spanCollection).insert(inserted.capture());
        var span = inserted.getValue();
        assertEquals(input, span.input);
        assertEquals(output, span.output);
        assertNull(span.attributes.get("langfuse.observation.input"));
        assertNull(span.attributes.get("langfuse.observation.output"));
        assertNull(span.attributes.get("gen_ai.prompt"));
        assertNull(span.attributes.get("gen_ai.completion"));
        assertEquals("agent", span.attributes.get("langfuse.observation.type"));
    }

    @Test
    void keepsLangfuseAttributeWhenItDiffersFromSpanPayload() {
        var service = service();
        when(service.traceCollection.find(any(Bson.class))).thenReturn(List.of()).thenReturn(List.of(new Trace()));
        when(service.spanCollection.find(any(Bson.class))).thenReturn(List.of());

        var attributeInput = "{\"messages\":[{\"role\":\"user\",\"content\":\"from attribute\"}]}";
        var spanInput = "{\"messages\":[{\"role\":\"user\",\"content\":\"from prompt\"}]}";
        service.ingest(request(span("chat",
            attr("gen_ai.prompt", spanInput),
            attr("langfuse.observation.input", attributeInput))));

        var inserted = ArgumentCaptor.forClass(Span.class);
        verify(service.spanCollection).insert(inserted.capture());
        assertEquals(spanInput, inserted.getValue().input);
        assertEquals(attributeInput, inserted.getValue().attributes.get("langfuse.observation.input"));
    }

    @Test
    void gatewaySpansWithSameSessionIdMergeIntoSameTrace() {
        var service = service();
        when(service.traceCollection.find(any(Bson.class))).thenReturn(List.of()).thenReturn(List.of(new Trace()));
        when(service.spanCollection.find(any(Bson.class))).thenReturn(List.of());

        service.ingest(request(span("gateway.chat.completions",
            attr("client.type", "gateway"),
            attr("session.id", "session-1"),
            attr("user.id", "user-1"))));
        service.ingest(request(span("gateway.chat.completions",
            attr("client.type", "gateway"),
            attr("session.id", "session-1"),
            attr("user.id", "user-1"))));

        var inserted = ArgumentCaptor.forClass(Span.class);
        verify(service.spanCollection, times(2)).insert(inserted.capture());
        var spans = inserted.getAllValues();
        assertEquals(spans.get(0).traceId, spans.get(1).traceId);
        // derived trace id replaces the random proto trace id (16 zero bytes in this test)
        assertNotEquals("0".repeat(32), spans.get(0).traceId);
        // only one trace doc is created for the whole session
        verify(service.traceCollection).insert(any(Trace.class));
    }

    @Test
    void gatewaySpansWithDifferentSessionIdsKeepSeparateTraces() {
        var service = service();
        when(service.traceCollection.find(any(Bson.class))).thenReturn(List.of()).thenReturn(List.of(new Trace()));
        when(service.spanCollection.find(any(Bson.class))).thenReturn(List.of());

        service.ingest(request(span("gateway.chat.completions",
            attr("client.type", "gateway"),
            attr("session.id", "session-1"),
            attr("user.id", "user-1"))));
        service.ingest(request(span("gateway.chat.completions",
            attr("client.type", "gateway"),
            attr("session.id", "session-2"),
            attr("user.id", "user-1"))));

        var inserted = ArgumentCaptor.forClass(Span.class);
        verify(service.spanCollection, times(2)).insert(inserted.capture());
        var spans = inserted.getAllValues();
        assertNotEquals(spans.get(0).traceId, spans.get(1).traceId);
    }

    @Test
    void nonGatewaySpansKeepProtoTraceId() {
        var service = service();
        when(service.traceCollection.find(any(Bson.class))).thenReturn(List.of()).thenReturn(List.of(new Trace()));
        when(service.spanCollection.find(any(Bson.class))).thenReturn(List.of());

        service.ingest(request(span("agent.run",
            attr("session.id", "chat-session-1"),
            attr("user.id", "user-1"))));

        var inserted = ArgumentCaptor.forClass(Span.class);
        verify(service.spanCollection).insert(inserted.capture());
        // agent spans with a session.id keep their real trace id (16 zero bytes in this test)
        assertEquals("0".repeat(32), inserted.getValue().traceId);
    }

    private OTLPIngestService service() {
        var service = new OTLPIngestService();
        service.traceCollection = traceCollection();
        service.spanCollection = spanCollection();
        service.agentRunCollection = agentRunCollection();
        service.chatSessionCollection = chatSessionCollection();
        service.modelPricingService = new ModelPricingService();
        service.modelPricingService.gatewayModelCollection = gatewayModelCollection();
        return service;
    }

    private ExportTraceServiceRequest request(io.opentelemetry.proto.trace.v1.Span span) {
        return ExportTraceServiceRequest.newBuilder()
            .addResourceSpans(ResourceSpans.newBuilder()
                .addScopeSpans(ScopeSpans.newBuilder().addSpans(span)))
            .build();
    }

    private io.opentelemetry.proto.trace.v1.Span span(String name, KeyValue... attrs) {
        var builder = io.opentelemetry.proto.trace.v1.Span.newBuilder()
            .setTraceId(ByteString.copyFrom(new byte[16]))
            .setSpanId(ByteString.copyFrom(new byte[8]))
            .setName(name)
            .setStartTimeUnixNano(1_000_000L)
            .setEndTimeUnixNano(2_000_000L);
        for (var attr : attrs) {
            builder.addAttributes(attr);
        }
        return builder.build();
    }

    private KeyValue attr(String key, String value) {
        return KeyValue.newBuilder()
            .setKey(key)
            .setValue(AnyValue.newBuilder().setStringValue(value))
            .build();
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<GatewayModelConfig> gatewayModelCollection() {
        return (MongoCollection<GatewayModelConfig>) mock(MongoCollection.class);
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<Trace> traceCollection() {
        return (MongoCollection<Trace>) mock(MongoCollection.class);
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<Span> spanCollection() {
        return (MongoCollection<Span>) mock(MongoCollection.class);
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<AgentRun> agentRunCollection() {
        return (MongoCollection<AgentRun>) mock(MongoCollection.class);
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<ChatSession> chatSessionCollection() {
        return (MongoCollection<ChatSession>) mock(MongoCollection.class);
    }
}
