package ai.core.server.trace.service;

import ai.core.server.domain.AgentRun;
import ai.core.server.domain.ChatSession;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OTLPIngestServiceTest {
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

    private OTLPIngestService service() {
        var service = new OTLPIngestService();
        service.traceCollection = traceCollection();
        service.spanCollection = spanCollection();
        service.agentRunCollection = agentRunCollection();
        service.chatSessionCollection = chatSessionCollection();
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
