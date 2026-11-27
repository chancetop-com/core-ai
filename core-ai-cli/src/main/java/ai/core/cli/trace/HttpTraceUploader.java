package ai.core.cli.trace;

import ai.core.cli.DebugLog;
import ai.core.cli.http.TlsTrust;
import ai.core.utils.JsonUtil;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Posts CLI traces through a process-wide bounded queue and one daemon consumer; every failure is only debug-logged.
 */
@SuppressFBWarnings("VO_VOLATILE_INCREMENT")
public class HttpTraceUploader implements TraceUploader {
    static final int MAX_QUEUE_ITEMS = 512;
    static final long MAX_QUEUE_BYTES = 8L * 1024 * 1024;
    static final int MAX_BATCH_ITEMS = 32;
    static final long MAX_BATCH_BYTES = 1L * 1024 * 1024;
    static final Duration DISABLE_TTL = Duration.ofMinutes(15);
    private static final String PATH = "/api/traces/ingest";
    private static final String START_PATH = "/api/traces/ingest/start";
    private static final Duration LIVE_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration FINAL_TIMEOUT = Duration.ofSeconds(10);
    private static final int LIVE_MAX_ATTEMPTS = 2;
    private static final int FINAL_MAX_ATTEMPTS = 3;
    private static final Duration RETRY_BASE_DELAY = Duration.ofMillis(200);
    private static final Duration FLUSH_TIMEOUT = Duration.ofSeconds(15);
    private static final long ITEM_OVERHEAD_BYTES = 256L;
    private static final AtomicBoolean UPLOADED = new AtomicBoolean();
    private static final UploadQueue SHARED_QUEUE = new UploadQueue(
            MAX_QUEUE_ITEMS, MAX_QUEUE_BYTES, System::currentTimeMillis, DISABLE_TTL, RETRY_BASE_DELAY);

    static Map<String, Object> toMap(CliTraceRequest request) {
        return toMap(request, List.of());
    }

    static Map<String, Object> toMap(CliTraceRequest request, List<CliTraceRequest.CliTracePayload> payloads) {
        var spans = new ArrayList<Map<String, Object>>(request.spans == null ? 0 : request.spans.size());
        if (request.spans != null) {
            for (var s : request.spans) spans.add(spanToMap(s));
        }
        var m = orderedMap("serviceName", request.serviceName, "serviceVersion", request.serviceVersion,
                "environment", request.environment, "clientType", request.clientType, "spans", spans);
        if (payloads != null && !payloads.isEmpty()) {
            var mapped = new ArrayList<Map<String, Object>>(payloads.size());
            for (var p : payloads) mapped.add(orderedMap("traceId", p.traceId, "dataUrl", p.dataUrl));
            m.put("payloads", mapped);
        }
        return m;
    }

    static Map<String, Object> startToMap(CliTraceStart s) {
        return orderedMap("traceId", s.traceId, "name", s.name, "input", s.input, "startedAtEpochMs", s.startedAtEpochMs, "serviceName", s.serviceName, "serviceVersion", s.serviceVersion, "environment", s.environment, "clientType", s.clientType);
    }

    private static Map<String, Object> spanToMap(CliTraceSpan s) {
        return orderedMap("traceId", s.traceId, "spanId", s.spanId, "parentSpanId", s.parentSpanId, "name", s.name, "type", s.type, "model", s.model, "resolvedModel", s.resolvedModel,
                "input", s.input, "output", s.output, "inputTokens", s.inputTokens, "outputTokens", s.outputTokens, "cachedTokens", s.cachedTokens, "reasoningTokens", s.reasoningTokens,
                "ttftMs", s.ttftMs, "firstContentMs", s.firstContentMs, "durationMs", s.durationMs, "status", s.status, "attributes", s.attributes, "startedAtEpochMs", s.startedAtEpochMs,
                "completedAtEpochMs", s.completedAtEpochMs);
    }

    private static Map<String, Object> orderedMap(Object... pairs) {
        var m = new LinkedHashMap<String, Object>(pairs.length / 2);
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    static String stripTrailingSlash(String url) {
        var u = url;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }
    static boolean retryable(int status) {
        return status == 408 || status == 429 || status >= 500;
    }
    private static String extractTraceId(CliTraceRequest request) {
        if (request.spans == null) return null;
        for (var span : request.spans) {
            if (span != null && span.traceId != null) return span.traceId;
        }
        return null;
    }
    private static long estimateSpanBytes(CliTraceSpan s) {
        long size = estimateStrings(s.traceId, s.spanId, s.parentSpanId, s.name, s.type,
                s.model, s.resolvedModel, s.input, s.output, s.status);
        if (s.attributes != null) {
            for (var e : s.attributes.entrySet()) {
                size += (e.getKey() == null ? 0L : e.getKey().length()) + (e.getValue() == null ? 0L : e.getValue().length());
            }
        }
        return size;
    }
    private static long estimateStrings(String... values) {
        long size = ITEM_OVERHEAD_BYTES;
        for (var value : values) {
            if (value != null) size += value.length();
        }
        return size;
    }
    public static boolean flush() {
        return flush(FLUSH_TIMEOUT);
    }
    public static boolean flush(Duration timeout) {
        if (!UPLOADED.get()) return true;
        return SHARED_QUEUE.flush(timeout);
    }

    private final String serverUrl;
    private final String apiKey;
    private final Path mediaRoot;
    private final Supplier<HttpClient> clientSupplier;
    private final UploadQueue queue;
    private volatile TraceMeta traceMeta;
    private Target target;

    public HttpTraceUploader(String serverUrl, String apiKey) {
        this(serverUrl, apiKey, false, null);
    }
    public HttpTraceUploader(String serverUrl, String apiKey, boolean insecure) {
        this(serverUrl, apiKey, insecure, null);
    }
    public HttpTraceUploader(String serverUrl, String apiKey, boolean insecure, Path mediaRoot) {
        this(serverUrl, apiKey, () -> insecure ? Clients.INSECURE : Clients.SECURE, mediaRoot, SHARED_QUEUE);
    }
    HttpTraceUploader(String serverUrl, String apiKey, Supplier<HttpClient> clientSupplier, Path mediaRoot, UploadQueue queue) {
        this.serverUrl = stripTrailingSlash(serverUrl);
        this.apiKey = apiKey;
        this.clientSupplier = clientSupplier;
        this.mediaRoot = mediaRoot;
        this.queue = queue;
    }

    @Override
    public void start(CliTraceStart start) {
        if (start == null) return;
        traceMeta = new TraceMeta(start.serviceName, start.serviceVersion, start.environment, start.clientType);
        if (queue.isLiveDisabled(serverUrl)) return;
        if (queue == SHARED_QUEUE) UPLOADED.set(true);
        long bytes = estimateStrings(start.traceId, start.name, start.input,
                start.serviceName, start.serviceVersion, start.environment, start.clientType);
        queue.enqueueNormal(new Outbound(Kind.START, target(), start.traceId, start, null, bytes), false);
    }

    @Override
    public void span(CliTraceSpan span) {
        if (span == null || queue.isLiveDisabled(serverUrl)) return;
        if (queue == SHARED_QUEUE) UPLOADED.set(true);
        queue.enqueueNormal(new Outbound(Kind.SPAN, target(), span.traceId, span, traceMeta, estimateSpanBytes(span)), true);
    }

    @Override
    public void upload(CliTraceRequest request) {
        if (request == null) return;
        if (queue == SHARED_QUEUE) UPLOADED.set(true);
        long bytes = estimateStrings(request.serviceName, request.serviceVersion, request.environment, request.clientType);
        if (request.spans != null) {
            for (var s : request.spans) {
                if (s != null) bytes += estimateSpanBytes(s);
            }
        }
        queue.enqueueFinal(new Outbound(Kind.FINAL, target(), extractTraceId(request), request, null, bytes));
    }

    private Target target() {
        synchronized (this) {
            if (target == null) {
                target = new Target(serverUrl, serverUrl + PATH, serverUrl + START_PATH, apiKey, clientSupplier.get(), mediaRoot);
            }
            return target;
        }
    }

    enum Kind { START, SPAN, FINAL, BARRIER }

    private record Target(String serverUrl, String endpoint, String startEndpoint,
                          String apiKey, HttpClient httpClient, Path mediaRoot) { }
    private record TraceMeta(String serviceName, String serviceVersion, String environment, String clientType) { }
    private record Outbound(Kind kind, Target target, String traceId, Object payload, TraceMeta meta, long estimatedBytes) { }

    static final class UploadQueue {
        private final int maxItems;
        private final long maxBytes;
        private final LongSupplier clockMs;
        private final Duration disableTtl;
        private final Duration retryBaseDelay;
        private final BlockingDeque<Outbound> deque = new LinkedBlockingDeque<>();
        private final ConcurrentMap<String, Long> disabledUntil = new ConcurrentHashMap<>();
        private int dataItems;
        private volatile long queuedBytes;
        private volatile long droppedSpans;
        private Thread worker;
        private volatile boolean stopped;

        UploadQueue(int maxItems, long maxBytes, LongSupplier clockMs, Duration disableTtl, Duration retryBaseDelay) {
            this.maxItems = maxItems;
            this.maxBytes = maxBytes;
            this.clockMs = clockMs;
            this.disableTtl = disableTtl;
            this.retryBaseDelay = retryBaseDelay;
        }

        boolean isLiveDisabled(String serverUrl) {
            var until = disabledUntil.get(serverUrl);
            if (until == null) return false;
            if (clockMs.getAsLong() < until) return true;
            disabledUntil.remove(serverUrl, until);
            return false;
        }

        void enqueueNormal(Outbound item, boolean isSpan) {
            synchronized (this) {
                if (dataItems >= maxItems || queuedBytes + item.estimatedBytes() > maxBytes) {
                    if (isSpan) droppedSpans++;
                    DebugLog.log(isSpan ? "trace span dropped: queue full (n=" + droppedSpans + ")" : "trace start dropped: queue full");
                    return;
                }
                enqueueLocked(item, false);
            }
        }

        void enqueueFinal(Outbound item) {
            synchronized (this) {
                if (item.traceId() != null) {
                    removeMatching(e -> (e.kind() == Kind.START || e.kind() == Kind.SPAN) && item.traceId().equals(e.traceId()), false, false);
                }
                while (dataItems >= maxItems || queuedBytes + item.estimatedBytes() > maxBytes && dataItems > 0) {
                    if (!removeMatching(e -> e.kind() == Kind.SPAN, true, true)) break;
                }
                enqueueLocked(item, true);
            }
        }

        boolean flush(Duration timeout) {
            var latch = new CountDownLatch(1);
            synchronized (this) {
                if (worker == null && deque.isEmpty()) return true;
                enqueueLocked(new Outbound(Kind.BARRIER, null, null, latch, null, 0L), false);
            }
            try {
                if (latch.await(Math.max(0L, timeout.toMillis()), TimeUnit.MILLISECONDS)) return true;
                DebugLog.log("trace upload flush did not finish");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                DebugLog.log("trace upload flush interrupted", e);
            }
            return false;
        }

        long droppedSpans() {
            return droppedSpans;
        }

        long queuedBytes() {
            return queuedBytes;
        }
        void shutdown() {
            Thread toInterrupt;
            synchronized (this) {
                stopped = true;
                toInterrupt = worker;
                notifyAll();
            }
            if (toInterrupt != null) toInterrupt.interrupt();
        }

        private void enqueueLocked(Outbound item, boolean first) {
            if (first) deque.offerFirst(item);
            else deque.offerLast(item);
            if (item.kind() != Kind.BARRIER) {
                dataItems++;
                queuedBytes += item.estimatedBytes();
            }
            if (worker == null && !stopped) {
                worker = new Thread(this::runLoop, "cli-trace-upload");
                worker.setDaemon(true);
                worker.start();
            }
            notifyAll();
        }

        private boolean removeMatching(Predicate<Outbound> predicate, boolean firstOnly, boolean countDropped) {
            var it = deque.iterator();
            boolean removed = false;
            while (it.hasNext()) {
                var existing = it.next();
                if (!predicate.test(existing)) continue;
                it.remove();
                dataItems--;
                queuedBytes -= existing.estimatedBytes();
                if (countDropped) DebugLog.log("trace span evicted for final batch (n=" + ++droppedSpans + ")");
                removed = true;
                if (firstOnly) return true;
            }
            return removed;
        }

        private void runLoop() {
            while (!stopped) {
                try {
                    var batch = takeNext();
                    if (batch == null) return;
                    processBatch(batch);
                } catch (InterruptedException e) {
                    if (stopped) Thread.currentThread().interrupt();
                }
            }
        }

        // Only coalesce contiguous head items with matching target, traceId and kind; never drainTo.
        private List<Outbound> takeNext() throws InterruptedException {
            synchronized (this) {
                while (!stopped && deque.isEmpty()) wait();
                if (deque.isEmpty()) return null;
                var first = deque.pollFirst();
                if (first.kind() == Kind.BARRIER) return List.of(first);
                dataItems--;
                queuedBytes -= first.estimatedBytes();
                if (first.kind() != Kind.SPAN) return List.of(first);
                var batch = new ArrayList<Outbound>();
                batch.add(first);
                long batchBytes = first.estimatedBytes();
                while (batch.size() < MAX_BATCH_ITEMS) {
                    var next = deque.peekFirst();
                    if (next == null || next.kind() != Kind.SPAN) break;
                    if (batchBytes + next.estimatedBytes() > MAX_BATCH_BYTES) break;
                    if (!first.target().equals(next.target()) || !Objects.equals(first.traceId(), next.traceId())) break;
                    deque.pollFirst();
                    dataItems--;
                    queuedBytes -= next.estimatedBytes();
                    batch.add(next);
                    batchBytes += next.estimatedBytes();
                }
                return batch;
            }
        }

        @SuppressFBWarnings("REC_CATCH_EXCEPTION")
        private void processBatch(List<Outbound> batch) {
            var first = batch.get(0);
            if (first.kind() == Kind.BARRIER) {
                ((CountDownLatch) first.payload()).countDown();
                return;
            }
            try {
                var t = first.target();
                switch (first.kind()) {
                    case START -> {
                        if (!isLiveDisabled(t.serverUrl())) sendJson(t, t.startEndpoint(), JsonUtil.toJson(startToMap((CliTraceStart) first.payload())), LIVE_TIMEOUT, LIVE_MAX_ATTEMPTS, true);
                    }
                    case SPAN -> sendSpans(t, batch);
                    case FINAL -> {
                        var req = (CliTraceRequest) first.payload();
                        sendJson(t, t.endpoint(), JsonUtil.toJson(toMap(req, CliTracePayloads.collect(req.spans, t.mediaRoot()))), FINAL_TIMEOUT, FINAL_MAX_ATTEMPTS, false);
                    }
                    default -> DebugLog.log("unexpected trace batch kind: " + first.kind());
                }
            } catch (Exception e) {
                DebugLog.log("trace upload error", e);
            }
        }

        private void sendSpans(Target target, List<Outbound> batch) {
            if (isLiveDisabled(target.serverUrl())) return;
            var meta = batch.get(0).meta();
            var req = new CliTraceRequest();
            req.serviceName = meta != null ? meta.serviceName() : null;
            req.serviceVersion = meta != null ? meta.serviceVersion() : null;
            req.environment = meta != null ? meta.environment() : null;
            req.clientType = meta != null ? meta.clientType() : null;
            req.spans = batch.stream().map(i -> (CliTraceSpan) i.payload()).toList();
            sendJson(target, target.endpoint(), JsonUtil.toJson(toMap(req, List.of())), LIVE_TIMEOUT, LIVE_MAX_ATTEMPTS, false);
        }

        private void sendJson(Target target, String url, String body, Duration timeout, int maxAttempts, boolean isStart) {
            var request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(timeout)
                    .header("Content-Type", "application/json").header("Authorization", "Bearer " + target.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                if (!sendOnce(target, request, attempt, maxAttempts, isStart) || !sleepBackoff(attempt)) return;
            }
        }

        @SuppressFBWarnings("REC_CATCH_EXCEPTION")
        private boolean sendOnce(Target target, HttpRequest httpRequest, int attempt, int maxAttempts, boolean isStart) {
            try {
                var response = target.httpClient().send(httpRequest, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 200) return false;
                if (isStart && (status == 404 || status == 405)) {
                    long until = clockMs.getAsLong() + disableTtl.toMillis();
                    disabledUntil.put(target.serverUrl(), until);
                    DebugLog.log("trace live disabled (server=" + target.serverUrl() + ", status=" + status + ", until=" + until + ")");
                    return false;
                }
                if (!retryable(status) || attempt == maxAttempts) {
                    DebugLog.log("trace upload failed: status=" + status + " body=" + response.body());
                    return false;
                }
                DebugLog.log("trace upload busy: status=" + status + ", attempt=" + attempt);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                DebugLog.log("trace upload interrupted", e);
                return false;
            } catch (Exception e) {
                DebugLog.log(attempt == maxAttempts ? "trace upload error" : "trace upload error, retrying: " + e.getMessage(), e);
                return attempt < maxAttempts;
            }
        }

        private boolean sleepBackoff(int attempt) {
            long delayMs = retryBaseDelay.toMillis() * attempt;
            if (delayMs <= 0) return true;
            try {
                Thread.sleep(delayMs);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                DebugLog.log("trace upload interrupted", e);
                return false;
            }
        }
    }

    private static final class Clients {
        private static final HttpClient SECURE = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        private static final HttpClient INSECURE = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).sslContext(TlsTrust.trustAllContext()).build();
    }
}
