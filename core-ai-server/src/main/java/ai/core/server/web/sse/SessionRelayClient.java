package ai.core.server.web.sse;

import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.server.messaging.SseEventCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Consumes the owner pod's internal SSE stream and forwards events to the local channel.
 *
 * @author stephen
 */
public final class SessionRelayClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionRelayClient.class);
    private static final long STALE_NANOS = 45_000_000_000L;

    private final String sessionId;
    private final String address;
    private final String token;
    private final long after;
    private final HttpClient httpClient;
    private final Listener listener;
    private volatile boolean closed;
    private volatile InputStream body;
    private volatile long lastActivityNanos = System.nanoTime();
    private volatile long lastSeq = -1;

    public SessionRelayClient(String sessionId, String address, String token, long after,
                              HttpClient httpClient, Listener listener) {
        this.sessionId = sessionId;
        this.address = address;
        this.token = token;
        this.after = after;
        this.httpClient = httpClient;
        this.listener = listener;
    }

    public void run() {
        var opened = false;
        var unavailableNotified = false;
        try {
            var request = HttpRequest.newBuilder(uri())
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("Accept", "text/event-stream")
                    .header("X-Internal-Token", token)
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                listener.onUnavailable(response.statusCode());
                unavailableNotified = true;
                return;
            }
            body = response.body();
            opened = true;
            listener.onOpen();
            read(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!closed) LOGGER.debug("session relay ended, sessionId={}, address={}, error={}", sessionId, address, e.getMessage());
        } catch (RuntimeException e) {
            if (!closed) LOGGER.warn("session relay failed, sessionId={}, address={}", sessionId, address, e);
        } finally {
            closeBody();
            if (!opened && !unavailableNotified && !closed) listener.onUnavailable(-1);
        }
    }

    private URI uri() {
        return URI.create("http://" + address + "/_internal/sessions/events?session_id="
                + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "&after=" + Math.max(after, 0));
    }

    private void read(InputStream input) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            long id = -1;
            StringBuilder data = null;
            while (!closed) {
                var line = reader.readLine();
                if (line == null) return;
                lastActivityNanos = System.nanoTime();
                if (line.isEmpty()) {
                    if (!dispatch(id, data)) return;
                    id = -1;
                    data = null;
                } else if (line.startsWith(":")) {
                    continue;
                } else if (line.startsWith("id:")) {
                    id = parseId(line.substring(3).trim());
                } else if (line.startsWith("data:")) {
                    var value = line.substring(5);
                    if (value.startsWith(" ")) value = value.substring(1);
                    if (data == null) data = new StringBuilder(value);
                    else data.append('\n').append(value);
                }
            }
        }
    }

    private boolean dispatch(long id, StringBuilder data) {
        if (data == null) return true;
        var event = SseEventCodec.fromJson(data.toString());
        if (event == null) return true;
        if (id > 0) lastSeq = id;
        if (listener.onEvent(id, event)) return true;
        close();
        return false;
    }

    private long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public void close() {
        closed = true;
        closeBody();
    }

    private void closeBody() {
        var current = body;
        body = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException e) {
                LOGGER.debug("failed to close session relay body, sessionId={}", sessionId, e);
            }
        }
    }

    public boolean isStale() {
        return System.nanoTime() - lastActivityNanos > STALE_NANOS;
    }

    public long lastSeq() {
        return lastSeq;
    }
    public interface Listener {
        void onOpen();

        boolean onEvent(long seq, SseBaseEvent event);

        void onUnavailable(int status);
    }

}
