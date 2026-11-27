package ai.core.cli.trace;

import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class HttpTraceUploaderTest {
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> spanIds(RecordedRequest req) {
        var spans = (List<Map<String, Object>>) req.bodyMap().get("spans");
        var ids = new ArrayList<String>(spans.size());
        for (var s : spans) {
            ids.add((String) s.get("spanId"));
        }
        return ids;
    }

    private static CliTraceStart startRecord(String traceId, String input) {
        var s = new CliTraceStart();
        s.traceId = traceId;
        s.name = "cli turn";
        s.input = input;
        s.startedAtEpochMs = 1234L;
        s.serviceName = "core-ai-cli";
        s.serviceVersion = "1";
        s.environment = "cli";
        s.clientType = "desktop";
        return s;
    }

    private static CliTraceSpan span(String traceId, String spanId, String type) {
        var s = new CliTraceSpan();
        s.traceId = traceId;
        s.spanId = spanId;
        s.parentSpanId = "root";
        s.name = type.toLowerCase(Locale.ROOT);
        s.type = type;
        s.status = "OK";
        s.startedAtEpochMs = 100L;
        s.completedAtEpochMs = 200L;
        return s;
    }

    private static CliTraceSpan rootSpan(String traceId, String spanId) {
        var s = span(traceId, spanId, "AGENT");
        s.parentSpanId = null;
        s.name = "cli turn";
        return s;
    }

    private static CliTraceRequest request(List<CliTraceSpan> spans) {
        var req = new CliTraceRequest();
        req.serviceName = "core-ai-cli";
        req.serviceVersion = "1";
        req.environment = "cli";
        req.clientType = "desktop";
        req.spans = spans;
        return req;
    }

    private HttpServer server;
    private String baseUrl;
    private HttpClient httpClient;
    private final List<HttpTraceUploader.UploadQueue> queues = new ArrayList<>();
    private final List<RecordedRequest> recorded = new CopyOnWriteArrayList<>();
    private final AtomicReference<Function<RecordedRequest, Integer>> statusHandler = new AtomicReference<>(r -> 200);

    @BeforeEach
    void setUp() throws Exception {
        recorded.clear();
        statusHandler.set(r -> 200);
        httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/traces/ingest", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        for (var queue : queues) {
            queue.shutdown();
        }
        queues.clear();
        if (server != null) server.stop(0);
    }

    @Test
    void retriesTransientServerFailuresOnly() {
        assertTrue(HttpTraceUploader.retryable(408));
        assertTrue(HttpTraceUploader.retryable(429));
        assertTrue(HttpTraceUploader.retryable(500));
        assertTrue(HttpTraceUploader.retryable(503));
        assertFalse(HttpTraceUploader.retryable(400));
        assertFalse(HttpTraceUploader.retryable(404));
        assertFalse(HttpTraceUploader.retryable(405));
        assertFalse(HttpTraceUploader.retryable(413));
        assertFalse(HttpTraceUploader.retryable(200));
    }

    @Test
    void toMapAndStartToMapKeepWireContract() {
        var span = span("trace-1", "span-1", "LLM");
        var req = request(List.of(span));
        var map = HttpTraceUploader.toMap(req);
        assertEquals("core-ai-cli", map.get("serviceName"));
        assertEquals("1", map.get("serviceVersion"));
        assertEquals("cli", map.get("environment"));
        assertEquals("desktop", map.get("clientType"));
        assertNull(map.get("payloads"));
        assertEquals(1, ((List<?>) map.get("spans")).size());

        var payload = new CliTraceRequest.CliTracePayload();
        payload.traceId = "trace-1";
        payload.dataUrl = "data:image/png;base64,AA==";
        var withPayloads = HttpTraceUploader.toMap(req, List.of(payload));
        assertEquals(1, ((List<?>) withPayloads.get("payloads")).size());

        var start = startRecord("trace-1", "hello");
        var startMap = HttpTraceUploader.startToMap(start);
        assertEquals("trace-1", startMap.get("traceId"));
        assertEquals("cli turn", startMap.get("name"));
        assertEquals("hello", startMap.get("input"));
        assertEquals(1234L, startMap.get("startedAtEpochMs"));
        assertEquals("core-ai-cli", startMap.get("serviceName"));
        assertEquals("1", startMap.get("serviceVersion"));
        assertEquals("cli", startMap.get("environment"));
        assertEquals("desktop", startMap.get("clientType"));
        assertTrue(JsonUtil.toJson(startMap).contains("\"traceId\":\"trace-1\""));
    }

    @Test
    void coalescesContiguousHeadSpansAndStopsAtDifferentTrace() throws Exception {
        var queue = newQueue(512, 8L * 1024 * 1024, new AtomicLong(1_000L));
        var uploader = newUploader(baseUrl, "k1", queue);
        var blockerStarted = new CountDownLatch(1);
        var releaseBlocker = new CountDownLatch(1);
        var first = new AtomicInteger();
        statusHandler.set(req -> {
            if (first.getAndIncrement() == 0) {
                blockerStarted.countDown();
                awaitQuietly(releaseBlocker);
            }
            return 200;
        });

        uploader.start(startRecord("blocker-trace", "init"));
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));

        uploader.span(span("trace-a", "a1", "LLM"));
        uploader.span(span("trace-a", "a2", "TOOL"));
        uploader.span(span("trace-a", "a3", "TOOL"));
        uploader.span(span("trace-b", "b1", "LLM"));
        uploader.span(span("trace-a", "a4", "LLM"));

        releaseBlocker.countDown();
        assertTrue(queue.flush(Duration.ofSeconds(10)));
        assertEquals(0L, queue.queuedBytes());

        var ingestReqs = recorded.stream().filter(r -> "/api/traces/ingest".equals(r.path)).toList();
        assertEquals(3, ingestReqs.size(), "a1..a3 coalesce, stop at b1, then a4 separately");
        assertEquals(List.of("a1", "a2", "a3"), spanIds(ingestReqs.get(0)));
        assertNull(ingestReqs.get(0).bodyMap().get("payloads"));
        assertEquals(List.of("b1"), spanIds(ingestReqs.get(1)));
        assertEquals(List.of("a4"), spanIds(ingestReqs.get(2)));
    }

    @Test
    void finalJumpsAheadAndRemovesPendingStartAndSpansForSameTrace() throws Exception {
        var queue = newQueue(512, 8L * 1024 * 1024, new AtomicLong(1_000L));
        var uploader = newUploader(baseUrl, "k1", queue);
        var blockerStarted = new CountDownLatch(1);
        var releaseBlocker = new CountDownLatch(1);
        var first = new AtomicInteger();
        statusHandler.set(req -> {
            if (first.getAndIncrement() == 0) {
                blockerStarted.countDown();
                awaitQuietly(releaseBlocker);
            }
            return 200;
        });

        uploader.start(startRecord("blocker-trace", "init"));
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));

        uploader.start(startRecord("trace-a", "q"));
        uploader.span(span("trace-a", "a1", "LLM"));
        uploader.span(span("trace-b", "b1", "LLM"));
        uploader.span(span("trace-a", "a2", "TOOL"));
        uploader.upload(request(List.of(rootSpan("trace-a", "root-a"), span("trace-a", "a1", "LLM"))));

        releaseBlocker.countDown();
        assertTrue(queue.flush(Duration.ofSeconds(10)));

        var afterBlocker = recorded.subList(1, recorded.size());
        assertEquals(2, afterBlocker.size(), "unsent start/spans of trace-a must be removed");
        assertEquals("/api/traces/ingest", afterBlocker.get(0).path);
        assertEquals(List.of("root-a", "a1"), spanIds(afterBlocker.get(0)), "final batch must jump ahead of trace-b");
        assertEquals("/api/traces/ingest", afterBlocker.get(1).path);
        assertEquals(List.of("b1"), spanIds(afterBlocker.get(1)));
    }

    @Test
    void queueFullDropsSpanButFinalStillEnqueuesByEvictingOldestSpan() throws Exception {
        var queue = newQueue(3, 8L * 1024 * 1024, new AtomicLong(1_000L));
        var uploader = newUploader(baseUrl, "k1", queue);
        var blockerStarted = new CountDownLatch(1);
        var releaseBlocker = new CountDownLatch(1);
        var first = new AtomicInteger();
        statusHandler.set(req -> {
            if (first.getAndIncrement() == 0) {
                blockerStarted.countDown();
                awaitQuietly(releaseBlocker);
            }
            return 200;
        });

        uploader.start(startRecord("blocker-trace", "init"));
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));

        uploader.span(span("t1", "s1", "LLM"));
        uploader.span(span("t2", "s2", "LLM"));
        uploader.span(span("t3", "s3", "LLM"));
        uploader.span(span("t4", "s4", "LLM"));
        assertEquals(1L, queue.droppedSpans(), "4th span must be dropped when queue is full");

        uploader.upload(request(List.of(rootSpan("t-final", "root-f"))));
        assertEquals(2L, queue.droppedSpans(), "final must evict oldest span to make room");

        releaseBlocker.countDown();
        assertTrue(queue.flush(Duration.ofSeconds(10)));
        assertEquals(0L, queue.queuedBytes());

        var afterBlocker = recorded.subList(1, recorded.size());
        assertEquals(3, afterBlocker.size());
        assertEquals(List.of("root-f"), spanIds(afterBlocker.get(0)));
        assertEquals(List.of("s2"), spanIds(afterBlocker.get(1)));
        assertEquals(List.of("s3"), spanIds(afterBlocker.get(2)));
    }

    @Test
    void disablesLiveOn404ForTtlAndRecoversWhileFinalContinues() {
        var clock = new AtomicLong(10_000L);
        var queue = newQueue(512, 8L * 1024 * 1024, clock);
        var uploader = newUploader(baseUrl + "/", "k1", queue);

        statusHandler.set(req -> "/api/traces/ingest/start".equals(req.path) ? 404 : 200);
        uploader.start(startRecord("t1", "q1"));
        assertTrue(queue.flush(Duration.ofSeconds(10)));
        assertEquals(1, recorded.size());

        uploader.start(startRecord("t2", "q2"));
        uploader.span(span("t2", "s1", "LLM"));
        clock.addAndGet(Duration.ofMinutes(14).toMillis());
        uploader.start(startRecord("t3", "q3"));
        uploader.span(span("t3", "s2", "LLM"));
        assertTrue(queue.flush(Duration.ofSeconds(10)));
        assertEquals(1, recorded.size(), "start and spans stay disabled during 15m TTL");

        uploader.upload(request(List.of(rootSpan("t2", "root-2"))));
        assertTrue(queue.flush(Duration.ofSeconds(10)));
        assertEquals(2, recorded.size(), "final must still send while live is disabled");
        assertEquals("/api/traces/ingest", recorded.get(1).path);

        clock.addAndGet(Duration.ofMinutes(1).toMillis());
        statusHandler.set(req -> 200);
        uploader.start(startRecord("t4", "q4"));
        uploader.span(span("t4", "s4", "LLM"));
        assertTrue(queue.flush(Duration.ofSeconds(10)));
        assertEquals(4, recorded.size());
        assertEquals("/api/traces/ingest/start", recorded.get(2).path);
        assertEquals("/api/traces/ingest", recorded.get(3).path);
    }

    @Test
    void disablesLiveOn405AndDoesNotDisableOn400() {
        var clock = new AtomicLong(10_000L);
        var queue = newQueue(512, 8L * 1024 * 1024, clock);
        var uploader = newUploader(baseUrl, "k1", queue);

        statusHandler.set(req -> "/api/traces/ingest/start".equals(req.path) ? 405 : 200);
        uploader.start(startRecord("t1", "q1"));
        assertTrue(queue.flush(Duration.ofSeconds(10)));
        assertEquals(1, recorded.size());

        uploader.span(span("t1", "s1", "LLM"));
        assertTrue(queue.flush(Duration.ofSeconds(10)));
        assertEquals(1, recorded.size(), "405 must disable live");

        uploader.upload(request(List.of(rootSpan("t1", "r1"))));
        assertTrue(queue.flush(Duration.ofSeconds(10)));
        assertEquals(2, recorded.size(), "final must still send when disabled by 405");

        clock.addAndGet(Duration.ofMinutes(15).toMillis());
        statusHandler.set(req -> "/api/traces/ingest/start".equals(req.path) ? 400 : 200);
        uploader.start(startRecord("t2", "q2"));
        assertTrue(queue.flush(Duration.ofSeconds(10)));
        assertEquals(3, recorded.size());

        uploader.span(span("t2", "s2", "LLM"));
        assertTrue(queue.flush(Duration.ofSeconds(10)));
        assertEquals(4, recorded.size(), "400 must not disable live");
    }

    @Test
    void enforcesRetryPoliciesForStartSpanAndFinal() {
        var queue = newQueue(512, 8L * 1024 * 1024, new AtomicLong(1_000L));
        var uploader = newUploader(baseUrl, "k1", queue);

        statusHandler.set(req -> 503);
        uploader.start(startRecord("t1", "q"));
        assertTrue(queue.flush(Duration.ofSeconds(10)));
        assertEquals(2, recorded.size(), "start retries once (2 attempts total)");

        recorded.clear();
        uploader.span(span("t1", "s1", "LLM"));
        assertTrue(queue.flush(Duration.ofSeconds(10)));
        assertEquals(2, recorded.size(), "span retries once (2 attempts total)");

        recorded.clear();
        uploader.upload(request(List.of(rootSpan("t1", "r1"))));
        assertTrue(queue.flush(Duration.ofSeconds(10)));
        assertEquals(3, recorded.size(), "final retries twice (3 attempts total)");

        recorded.clear();
        var attempts = new AtomicInteger();
        statusHandler.set(req -> {
            int n = attempts.incrementAndGet();
            if (n == 1) return 408;
            return n == 2 ? 429 : 200;
        });
        uploader.upload(request(List.of(rootSpan("t2", "r2"))));
        assertTrue(queue.flush(Duration.ofSeconds(10)));
        assertEquals(3, recorded.size());

        recorded.clear();
        statusHandler.set(req -> 400);
        uploader.upload(request(List.of(rootSpan("t3", "r3"))));
        assertTrue(queue.flush(Duration.ofSeconds(10)));
        assertEquals(1, recorded.size());
    }

    @Test
    void flushWaitsForQueueDrainAndReturnsFalseOnTimeout() throws Exception {
        var queue = newQueue(512, 8L * 1024 * 1024, new AtomicLong(1_000L));
        var uploader = newUploader(baseUrl, "k1", queue);
        var uploadStarted = new CountDownLatch(1);
        var finishUpload = new CountDownLatch(1);
        statusHandler.set(req -> {
            uploadStarted.countDown();
            awaitQuietly(finishUpload);
            return 200;
        });

        uploader.upload(request(List.of(rootSpan("t1", "r1"))));
        assertTrue(uploadStarted.await(5, TimeUnit.SECONDS));

        assertFalse(queue.flush(Duration.ofMillis(100)), "flush must return false when timeout expires");

        var flushed = CompletableFuture.supplyAsync(() -> queue.flush(Duration.ofSeconds(10)));
        Thread.sleep(100);
        assertFalse(flushed.isDone(), "flush must wait for the in-flight upload");

        finishUpload.countDown();
        assertTrue(flushed.get(10, TimeUnit.SECONDS), "flush should return true once drained");
    }

    @Test
    void multipleUploadersShareQueueWithoutMixingApiKeysOrCoalescing() throws Exception {
        var queue = newQueue(512, 8L * 1024 * 1024, new AtomicLong(1_000L));
        var u1 = newUploader(baseUrl, "key-one", queue);
        var u2 = newUploader(baseUrl, "key-two", queue);
        var blockerStarted = new CountDownLatch(1);
        var releaseBlocker = new CountDownLatch(1);
        var first = new AtomicInteger();
        statusHandler.set(req -> {
            if (first.getAndIncrement() == 0) {
                blockerStarted.countDown();
                awaitQuietly(releaseBlocker);
            }
            return 200;
        });

        u1.start(startRecord("blocker", "init"));
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));

        u1.span(span("shared-trace", "s1", "LLM"));
        u2.span(span("shared-trace", "s2", "LLM"));

        releaseBlocker.countDown();
        assertTrue(queue.flush(Duration.ofSeconds(10)));

        var afterBlocker = recorded.subList(1, recorded.size());
        assertEquals(2, afterBlocker.size(), "spans with different targets must not coalesce");
        assertEquals("Bearer key-one", afterBlocker.get(0).authorization);
        assertEquals(List.of("s1"), spanIds(afterBlocker.get(0)));
        assertEquals("Bearer key-two", afterBlocker.get(1).authorization);
        assertEquals(List.of("s2"), spanIds(afterBlocker.get(1)));
    }

    private HttpTraceUploader.UploadQueue newQueue(int maxItems, long maxBytes, AtomicLong clock) {
        var queue = new HttpTraceUploader.UploadQueue(maxItems, maxBytes,
                clock::get, HttpTraceUploader.DISABLE_TTL, Duration.ZERO);
        queues.add(queue);
        return queue;
    }

    private HttpTraceUploader newUploader(String url, String apiKey, HttpTraceUploader.UploadQueue queue) {
        return new HttpTraceUploader(url, apiKey, () -> httpClient, null, queue);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        var body = new String(bytes, StandardCharsets.UTF_8);
        var req = new RecordedRequest(exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"), body);
        recorded.add(req);
        int status = statusHandler.get().apply(req);
        byte[] resp = (status == 200 ? "ok" : "err").getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, resp.length);
        exchange.getResponseBody().write(resp);
        exchange.close();
    }

    private record RecordedRequest(String path, String authorization, String body) {
        private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
        };

        Map<String, Object> bodyMap() {
            return JsonUtil.fromJson(MAP_TYPE, body);
        }
    }
}
