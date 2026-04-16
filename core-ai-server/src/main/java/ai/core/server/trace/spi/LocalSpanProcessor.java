package ai.core.server.trace.spi;

import io.opentelemetry.context.Context;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.common.CompletableResultCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author stephen
 */
public class LocalSpanProcessor implements SpanProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalSpanProcessor.class);
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "local-span-processor");
        t.setDaemon(true);
        return t;
    });

    private final String serviceName;
    private final String serviceVersion;
    private final String environment;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    public LocalSpanProcessor(String serviceName, String serviceVersion, String environment) {
        this.serviceName = serviceName;
        this.serviceVersion = serviceVersion;
        this.environment = environment;
        LOGGER.debug("LocalSpanProcessor initialized - service: {}, version: {}, environment: {}",
                serviceName, serviceVersion, environment);
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        // No-op: span just started, we only need to process onEnd
    }

    @Override
    public void onEnd(ReadableSpan span) {
        if (isShutdown.get()) return;

        var ingestService = LocalSpanProcessorRegistry.getIngestService();
        if (ingestService == null) {
            LOGGER.warn("OTLPIngestService not registered, skipping span: {}", span.getName());
            return;
        }

        var spanName = span.getName();
        CompletableFuture.runAsync(() -> {
            try {
                long startTime = System.nanoTime();
                var request = convertToExportRequest(span);
                ingestService.ingest(request);
                long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
                LOGGER.debug("ingest completed, span={}, elapsed={}ms", spanName, elapsedMs);
            } catch (Exception e) {
                LOGGER.warn("Failed to write span locally: {}", spanName, e);
            }
        }, EXECUTOR);
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public CompletableResultCode shutdown() {
        isShutdown.set(true);
        return CompletableResultCode.ofSuccess();
    }

    public CompletableResultCode forceFlush(long timeoutNanos) {
        // No-op: we write immediately onEnd
        return CompletableResultCode.ofSuccess();
    }

    private ExportTraceServiceRequest convertToExportRequest(ReadableSpan span) {
        var spanData = span.toSpanData();
        var keyValueList = convertAttributes(spanData);
        var protoSpan = buildProtoSpan(spanData, keyValueList);
        var resource = buildResource();
        var scopeSpans = buildScopeSpans(protoSpan);
        var resourceSpans = ResourceSpans.newBuilder()
            .setResource(resource)
            .addScopeSpans(scopeSpans)
            .build();

        return ExportTraceServiceRequest.newBuilder()
            .addResourceSpans(resourceSpans)
            .build();
    }

    private List<KeyValue> convertAttributes(SpanData spanData) {
        var attributes = spanData.getAttributes();
        var keyValueList = new ArrayList<KeyValue>();
        if (attributes != null && !attributes.isEmpty()) {
            attributes.forEach((key, val) -> {
                var anyValue = convertAttributeValue(val);
                keyValueList.add(KeyValue.newBuilder()
                    .setKey(key.getKey())
                    .setValue(anyValue)
                    .build());
            });
        }
        return keyValueList;
    }

    private Span buildProtoSpan(SpanData spanData, List<KeyValue> keyValueList) {
        var traceIdHex = spanData.getTraceId();
        var spanIdHex = spanData.getSpanId();
        var traceIdBytes = hexToBytes(traceIdHex);
        var spanIdBytes = hexToBytes(spanIdHex);

        return Span.newBuilder()
            .setTraceId(com.google.protobuf.ByteString.copyFrom(traceIdBytes))
            .setSpanId(com.google.protobuf.ByteString.copyFrom(spanIdBytes))
            .setName(spanData.getName())
            .setKind(Span.SpanKind.SPAN_KIND_INTERNAL)
            .setStartTimeUnixNano(spanData.getStartEpochNanos())
            .setEndTimeUnixNano(spanData.getEndEpochNanos())
            .addAllAttributes(keyValueList)
            .setStatus(mapStatus(spanData))
            .build();
    }

    private Resource buildResource() {
        var resourceAttrs = List.of(
            KeyValue.newBuilder()
                .setKey("service.name")
                .setValue(AnyValue.newBuilder().setStringValue(serviceName).build())
                .build(),
            KeyValue.newBuilder()
                .setKey("service.version")
                .setValue(AnyValue.newBuilder().setStringValue(serviceVersion).build())
                .build(),
            KeyValue.newBuilder()
                .setKey("deployment.environment")
                .setValue(AnyValue.newBuilder().setStringValue(environment).build())
                .build()
        );

        return Resource.newBuilder()
            .addAllAttributes(resourceAttrs)
            .build();
    }

    private ScopeSpans buildScopeSpans(Span protoSpan) {
        var instrumentationScope = InstrumentationScope.newBuilder()
            .setName("core-ai")
            .setVersion("")
            .build();

        return ScopeSpans.newBuilder()
            .setScope(instrumentationScope)
            .addSpans(protoSpan)
            .build();
    }

    private byte[] hexToBytes(String hex) {
        var bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private AnyValue convertAttributeValue(Object value) {
        var builder = AnyValue.newBuilder();
        if (value instanceof String) {
            builder.setStringValue((String) value);
        } else if (value instanceof Long) {
            builder.setIntValue((Long) value);
        } else if (value instanceof Double) {
            builder.setDoubleValue((Double) value);
        } else if (value instanceof Boolean) {
            builder.setBoolValue((Boolean) value);
        } else {
            builder.setStringValue(value != null ? value.toString() : "");
        }
        return builder.build();
    }

    private io.opentelemetry.proto.trace.v1.Status mapStatus(SpanData spanData) {
        var statusCode = spanData.getStatus().getStatusCode();
        var otlpStatusCode = switch (statusCode) {
            case OK, UNSET -> io.opentelemetry.proto.trace.v1.Status.StatusCode.STATUS_CODE_OK;
            case ERROR -> io.opentelemetry.proto.trace.v1.Status.StatusCode.STATUS_CODE_ERROR;
        };

        return io.opentelemetry.proto.trace.v1.Status.newBuilder()
            .setCode(otlpStatusCode)
            .setMessage(spanData.getStatus().getDescription())
            .build();
    }
}