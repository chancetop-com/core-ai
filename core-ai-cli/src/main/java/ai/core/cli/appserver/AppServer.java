package ai.core.cli.appserver;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * stdio JSON-RPC transport for the app-server engine: framing, the initialize gate, heartbeat and
 * shutdown live here; everything else is delegated to {@link EngineApi}. One frame is one line, the
 * stream carries protocol frames only (startup code must keep stdout clean).
 *
 * @author stephen
 */
public class AppServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(AppServer.class);
    private static final int MAX_FRAME_CHARS = 2 * 1024 * 1024;
    private static final long DEFAULT_HEARTBEAT_MILLIS = 10_000L;

    private final BufferedReader reader;
    private final Writer writer;
    private final EngineApi engine;
    private final Object writeLock = new Object();
    private final AtomicBoolean initialized = new AtomicBoolean();

    private volatile boolean running = true;
    private volatile boolean readyPending;
    private volatile boolean shutdownRequested;
    private volatile long heartbeatMillis = DEFAULT_HEARTBEAT_MILLIS;
    private volatile ScheduledExecutorService heartbeat;

    public AppServer(InputStream in, OutputStream out, EngineApi engine) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        this.engine = engine;
        engine.attach(this::notifyImpl);
    }

    /** Overridable for tests so the heartbeat can be observed without waiting ten seconds. */
    public void setHeartbeatMillis(long millis) {
        this.heartbeatMillis = millis;
    }

    public int run() {
        try {
            while (running) {
                var line = readLine();
                if (line == null) break;
                if (line.isBlank()) continue;
                handleLine(line);
                if (!running) break;
            }
        } finally {
            stopHeartbeat();
            engine.shutdown();
        }
        return 0;
    }

    private String readLine() {
        try {
            return reader.readLine();
        } catch (IOException e) {
            LOGGER.warn("app-server stdin read failed: {}", e.getMessage());
            return null;
        }
    }

    private void handleLine(String line) {
        if (line.length() > MAX_FRAME_CHARS) {
            send(JsonRpcCodec.error(null, RpcException.INVALID_REQUEST,
                    "frame exceeds " + MAX_FRAME_CHARS + " characters", null));
            return;
        }
        JsonRpcCodec.Request request;
        try {
            request = JsonRpcCodec.parse(line);
        } catch (RpcException e) {
            send(JsonRpcCodec.error(null, e.code(), e.getMessage(), e.data()));
            return;
        }
        try {
            var result = dispatch(request);
            sendResult(request, result);
            afterResponse();
        } catch (RpcException e) {
            if (!request.notification()) {
                send(JsonRpcCodec.error(request.id(), e.code(), e.getMessage(), e.data()));
            }
        } catch (RuntimeException e) {
            LOGGER.warn("app-server request failed, method={}", request.method(), e);
            if (!request.notification()) {
                send(JsonRpcCodec.error(request.id(), RpcException.INTERNAL_ERROR, String.valueOf(e.getMessage()), null));
            }
        }
    }

    private void sendResult(JsonRpcCodec.Request request, JsonNode result) {
        if (!request.notification()) {
            send(JsonRpcCodec.response(request.id(), result));
        }
    }

    private JsonNode dispatch(JsonRpcCodec.Request request) {
        var method = request.method();
        if ("initialize".equals(method)) {
            if (initialized.get()) {
                throw RpcException.invalidRequest("initialize has already been called");
            }
            var result = engine.initialize(request.params());
            initialized.set(true);
            readyPending = true;
            startHeartbeat();
            return result;
        }
        if (!initialized.get()) {
            throw RpcException.invalidRequest("initialize must be the first request");
        }
        if ("shutdown".equals(method)) {
            shutdownRequested = true;
            return JsonRpcCodec.emptyParams();
        }
        if (!engine.supportedMethods().contains(method)) {
            throw RpcException.methodNotFound(method);
        }
        return engine.call(method, request.params());
    }

    private void afterResponse() {
        if (readyPending) {
            readyPending = false;
            notifyImpl("engine/ready", JsonRpcCodec.emptyParams());
        }
        if (shutdownRequested) {
            running = false;
        }
    }

    private void startHeartbeat() {
        var executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "app-server-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> notifyImpl("engine/heartbeat", JsonRpcCodec.emptyParams()),
                heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
        heartbeat = executor;
    }

    private void stopHeartbeat() {
        var executor = heartbeat;
        heartbeat = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void send(JsonNode frame) {
        var text = frame.toString();
        synchronized (writeLock) {
            try {
                writer.write(text);
                writer.write('\n');
                writer.flush();
            } catch (IOException e) {
                LOGGER.warn("app-server stdout write failed: {}", e.getMessage());
                running = false;
            }
        }
    }

    private void notifyImpl(String method, JsonNode params) {
        send(JsonRpcCodec.notification(method, params));
    }
}
