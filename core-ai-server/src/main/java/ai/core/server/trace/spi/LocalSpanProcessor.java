package ai.core.server.trace.spi;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author stephen
 */
public class LocalSpanProcessor implements SpanProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalSpanProcessor.class);
    // Span ingest is a public endpoint: a count bound alone is not a memory bound when one span can
    // carry megabytes, so the queue also has a byte budget and a per-span ceiling. Drops are counted
    // (with their bytes); capacity is fixed at class load — changing it needs a restart.
    private static final int INGEST_QUEUE_CAPACITY = 10000;
    private static final long INGEST_QUEUE_MAX_BYTES = 64L * 1024 * 1024;
    private static final long MAX_SPAN_BYTES = 1024 * 1024;
    // trace creation must not queue behind (or be dropped with) a span backlog, so it gets its own lane
    private static final int TRACE_QUEUE_CAPACITY = 2000;
    private static final AtomicLong DROPPED_SPANS = new AtomicLong();
    private static final AtomicLong DROPPED_SPAN_BYTES = new AtomicLong();
    private static final AtomicLong DROPPED_OVERSIZE_SPANS = new AtomicLong();
    private static final AtomicLong QUEUED_BYTES = new AtomicLong();
    private static final ThreadPoolExecutor EXECUTOR = createExecutor("local-span-processor", 2, INGEST_QUEUE_CAPACITY);
    private static final ThreadPoolExecutor TRACE_EXECUTOR = createExecutor("local-trace-processor", 1, TRACE_QUEUE_CAPACITY);
    // configured limit, applied once at startup: the pool queue is already built by then
    private static volatile int configuredCapacity;
    private static volatile long configuredMaxBytes;
    private static volatile long configuredMaxSpanBytes;
    private static final Set<String> IDENTITY_ATTRIBUTES = Set.of(
        "user.id", "session.id", "gen_ai.agent.id", "gen_ai.agent.name");

    /** Applied at startup; the queue that backs the executor cannot be resized afterwards. */
    public static void configureCapacity(int capacity) {
        if (capacity > 0) configuredCapacity = Math.min(capacity, INGEST_QUEUE_CAPACITY);
    }

    /** Applied at startup; non-positive values restore the shipped defaults. */
    public static void configureBudget(long maxQueueBytes, long maxSpanBytes) {
        configuredMaxBytes = maxQueueBytes > 0 ? Math.min(maxQueueBytes, INGEST_QUEUE_MAX_BYTES) : 0;
        configuredMaxSpanBytes = maxSpanBytes > 0 ? Math.min(maxSpanBytes, MAX_SPAN_BYTES) : 0;
    }

    /** Runs the task on the lane; false means the pool rejected it, so the caller reports the drop. */
    private static boolean submit(ThreadPoolExecutor executor, Runnable task) {
        try {
            CompletableFuture.runAsync(task, executor);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean accepts(ThreadPoolExecutor executor, int capacity) {
        return executor.getQueue().size() < capacity;
    }

    private static int effectiveCapacity() {
        return configuredCapacity > 0 ? configuredCapacity : INGEST_QUEUE_CAPACITY;
    }

    private static long effectiveMaxBytes() {
        return configuredMaxBytes > 0 ? configuredMaxBytes : INGEST_QUEUE_MAX_BYTES;
    }

    private static long effectiveMaxSpanBytes() {
        return configuredMaxSpanBytes > 0 ? configuredMaxSpanBytes : MAX_SPAN_BYTES;
    }

    /** Reserves room for one span; the submit may still be rejected, in which case the caller releases. */
    private static boolean reserve(long bytes) {
        if (EXECUTOR.getQueue().size() >= effectiveCapacity()) return false;
        if (QUEUED_BYTES.addAndGet(bytes) <= effectiveMaxBytes()) return true;
        QUEUED_BYTES.addAndGet(-bytes);
        return false;
    }

    private static void release(long bytes) {
        QUEUED_BYTES.addAndGet(-bytes);
    }

    private static void dropSpan(String stage, String spanName, long bytes) {
        var dropped = DROPPED_SPANS.incrementAndGet();
        DROPPED_SPAN_BYTES.addAndGet(bytes);
        if ((dropped & 0x3F) == 1) {
            LOGGER.warn("dropping span, stage={}, span={}, bytes={}, droppedTotal={}, droppedBytes={}, queuedBytes={}",
                    stage, spanName, bytes, dropped, DROPPED_SPAN_BYTES.get(), QUEUED_BYTES.get());
        }
    }

    private static ThreadPoolExecutor createExecutor(String threadName, int threads, int capacity) {
        var counter = new AtomicInteger();
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(capacity),
            r -> {
                var t = new Thread(r, threadName + "-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            });
    }

    /**
     * Approximate heap held by one span while it waits in the queue: the name, attribute keys and
     * string values plus a fixed overhead. Deliberately not a protobuf serialization.
     */
    private static long estimateSpanBytes(ReadableSpan span) {
        long bytes = 256 + length(span.getName());
        var attributes = span.getAttributes();
        if (attributes != null && !attributes.isEmpty()) {
            for (var entry : attributes.asMap().entrySet()) {
                bytes += length(entry.getKey().getKey()) + 32;
                var value = entry.getValue();
                if (value instanceof String text) {
                    bytes += text.length();
                } else if (value instanceof List<?> values) {
                    bytes += values.stream().filter(String.class::isInstance)
                            .mapToLong(item -> ((String) item).length()).sum();
                } else {
                    bytes += 8;
                }
            }
        }
        return bytes;
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    public static long droppedSpans() {
        return DROPPED_SPANS.get();
    }

    public static long droppedSpanBytes() {
        return DROPPED_SPAN_BYTES.get();
    }

    public static long droppedOversizeSpans() {
        return DROPPED_OVERSIZE_SPANS.get();
    }

    public static long queuedBytes() {
        return QUEUED_BYTES.get();
    }

    public static int ingestQueueDepth() {
        return EXECUTOR.getQueue().size();
    }

    /** Effective queue bound (the configured one, capped by what the pool was built with). */
    public static int ingestQueueCapacity() {
        return effectiveCapacity();
    }

    /** Effective byte budget for the ingest queue. */
    public static long ingestQueueMaxBytes() {
        return effectiveMaxBytes();
    }

    /** Effective per-span ceiling; a larger span is dropped before it enters the queue. */
    public static long maxSpanBytes() {
        return effectiveMaxSpanBytes();
    }

    private static boolean hasIdentity(Attributes attributes) {
        for (var key : IDENTITY_ATTRIBUTES) {
            if (attributes.get(AttributeKey.stringKey(key)) != null) return true;
        }
        return false;
    }

    private static Map<String, String> toAttributeMap(Attributes attributes) {
        var result = new HashMap<String, String>();
        attributes.forEach((key, value) -> result.put(key.getKey(), value != null ? value.toString() : null));
        return result;
    }

    private static boolean isRoot(Context parentContext) {
        return !io.opentelemetry.api.trace.Span.fromContext(parentContext).getSpanContext().isValid();
    }

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

    // A trace is created the moment its root span starts, because the identity it is attributed by
    // (user, session, agent) only ever exists on that span: child spans carry none and reach the store as
    // soon as they end, which used to leave a running turn or run unattributed until its very last span.
    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        if (isShutdown.get() || !isRoot(parentContext)) return;
        var attributes = span.getAttributes();
        if (!hasIdentity(attributes)) return;

        var ingestService = LocalSpanProcessorRegistry.getIngestService();
        if (ingestService == null) return;

        var traceId = span.getSpanContext().getTraceId();
        var spanName = span.getName();
        if (!accepts(TRACE_EXECUTOR, TRACE_QUEUE_CAPACITY)) {
            dropSpan("traceStarted", spanName, 0);
            return;
        }
        var startEpochMs = TimeUnit.NANOSECONDS.toMillis(span.toSpanData().getStartEpochNanos());
        var attrs = toAttributeMap(attributes);
        var resourceAttrs = resourceAttributes();
        if (!submit(TRACE_EXECUTOR, () -> {
            try {
                ingestService.traceStarted(traceId, spanName, startEpochMs, attrs, resourceAttrs);
            } catch (Exception e) {
                LOGGER.warn("Failed to create trace for started span: {}", spanName, e);
            }
        })) {
            dropSpan("traceStarted", spanName, 0);
        }
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
        var bytes = estimateSpanBytes(span);
        if (bytes > effectiveMaxSpanBytes()) {
            DROPPED_OVERSIZE_SPANS.incrementAndGet();
            dropSpan("spanOversize", spanName, bytes);
            return;
        }
        if (!reserve(bytes)) {
            dropSpan("spanEnded", spanName, bytes);
            return;
        }
        var ingested = submit(EXECUTOR, () -> {
            try {
                long startTime = System.nanoTime();
                var request = convertToExportRequest(span);
                ingestService.ingest(request);
                long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
                LOGGER.debug("ingest completed, span={}, elapsed={}ms", spanName, elapsedMs);
            } catch (Exception e) {
                LOGGER.warn("Failed to write span locally: {}", spanName, e);
            } finally {
                release(bytes);
            }
        });
        if (!ingested) {
            // the pool rejected the task between the reservation and the submit
            release(bytes);
            dropSpan("spanEnded", spanName, bytes);
        }
    }

    @Override
    public boolean isStartRequired() {
        return true;
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

    private Map<String, String> resourceAttributes() {
        return Map.of(
            "service.name", serviceName,
            "service.version", serviceVersion,
            "deployment.environment", environment);
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

        var builder = Span.newBuilder()
            .setTraceId(com.google.protobuf.ByteString.copyFrom(traceIdBytes))
            .setSpanId(com.google.protobuf.ByteString.copyFrom(spanIdBytes))
            .setName(spanData.getName())
            .setKind(Span.SpanKind.SPAN_KIND_INTERNAL)
            .setStartTimeUnixNano(spanData.getStartEpochNanos())
            .setEndTimeUnixNano(spanData.getEndEpochNanos())
            .addAllAttributes(keyValueList)
            .setStatus(mapStatus(spanData));

        var parentSpanContext = spanData.getParentSpanContext();
        if (parentSpanContext != null && parentSpanContext.isValid()) {
            builder.setParentSpanId(com.google.protobuf.ByteString.copyFrom(hexToBytes(parentSpanContext.getSpanId())));
        }

        return builder.build();
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
