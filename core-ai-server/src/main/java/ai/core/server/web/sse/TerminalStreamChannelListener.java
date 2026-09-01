package ai.core.server.web.sse;

import ai.core.server.sandbox.terminal.SandboxTerminalClient;
import ai.core.server.sandbox.terminal.SandboxTerminalService;
import ai.core.server.sandbox.terminal.TerminalRuntimeUnavailableException;
import ai.core.server.web.auth.AuthContext;
import ai.core.sse.RawSseChannel;
import core.framework.http.EventSource;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPClientException;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.WebContext;
import core.framework.web.exception.TooManyRequestsException;
import core.framework.web.sse.Channel;
import core.framework.web.sse.ChannelListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Bridges the sandbox runtime's terminal SSE stream (output/lifecycle events)
 * to the browser, mirroring {@code GatewayProxyService.streamEvents}'s
 * client/threading model: a dedicated long-read-timeout {@link HTTPClient}
 * and a pump loop that runs synchronously on the SSE connect thread (no
 * executor offload) until the upstream stream ends or the browser channel
 * stops accepting events.
 * <p>
 * Identifiers travel as query params ({@code agent-session-id}/{@code
 * sandbox-id}/{@code terminal-id}), not path params -- the SSE listen
 * framework only supports static registration paths. All authorization
 * error mapping (gate disabled, forbidden, REPLACED/MISSING) rides on the
 * exceptions {@link SandboxTerminalService#authorize} already throws:
 * core-ai's SSE handler converts any RuntimeException thrown from {@code
 * onConnect} into an SSE {@code error} event and closes the channel, so no
 * additional mapping is needed here. A per-Pod stream cap (default 50,
 * overridable via the package-private constructor for tests) rejects excess
 * connections with {@link TooManyRequestsException} (429) before any upstream
 * connection is attempted.
 *
 * @author xander
 */
public class TerminalStreamChannelListener implements ChannelListener<Object> {
    private static final Logger LOGGER = LoggerFactory.getLogger(TerminalStreamChannelListener.class);
    private static final int DEFAULT_MAX_STREAMS = 50;
    private static final String LAST_EVENT_ID_HEADER = "Last-Event-ID";
    private static final String TERMINAL_ID_CONTEXT_KEY = "terminal-id";
    // shared client with a long read timeout: a bridged terminal stream is open-ended with
    // expected idle periods, unlike a bounded chat completion stream; mirrors GatewayProxyService's
    // dedicated streaming HTTPClient (GatewayProxyService.java:43-46)
    private static final HTTPClient CLIENT = HTTPClient.builder()
            .connectTimeout(Duration.ofSeconds(10))
            .timeout(Duration.ofMinutes(10))
            .build();

    private final StreamSlots slots;

    @Inject
    SandboxTerminalService service;
    @Inject
    WebContext webContext;

    // CoreNG's BeanFactory requires bound classes to declare exactly one constructor,
    // so the per-pod cap is fixed here rather than constructor-injected.
    public TerminalStreamChannelListener() {
        this.slots = new StreamSlots(DEFAULT_MAX_STREAMS);
    }

    @Override
    public void onConnect(Request request, Channel<Object> channel, String lastEventId) {
        var params = TerminalStreamParams.parse(request);
        var userId = AuthContext.userId(webContext);
        var client = service.authorize(params.sessionId(), params.sandboxId(), userId);
        channel.context().put(TERMINAL_ID_CONTEXT_KEY, params.terminalId());
        var rawChannel = (RawSseChannel<?>) channel;

        if (!slots.acquire()) {
            throw new TooManyRequestsException("terminal stream cap reached on this pod");
        }
        try {
            pump(client, params.terminalId(), lastEventId, rawChannel);
        } catch (TerminalRuntimeUnavailableException e) {
            service.invalidateAddress(params.sandboxId());
            throw e;
        } finally {
            slots.release();
        }
        channel.close();
    }

    @Override
    public void onClose(Channel<Object> channel) {
        LOGGER.info("terminal SSE bridge disconnected, terminalId={}", channel.context().get(TERMINAL_ID_CONTEXT_KEY));
    }

    private void pump(SandboxTerminalClient client, String terminalId, String lastEventId, RawSseChannel<?> channel) {
        var httpRequest = buildRequest(client.eventsUrl(terminalId), lastEventId);
        try (var source = connect(httpRequest)) {
            for (var event : source) {
                if (!channel.sendRawEvent(event.id(), event.type(), event.data())) break;
            }
        }
    }

    private EventSource connect(HTTPRequest request) {
        try {
            return CLIENT.sse(request);
        } catch (HTTPClientException e) {
            throw new TerminalRuntimeUnavailableException("terminal runtime unreachable: " + e.getMessage(), e);
        }
    }

    private HTTPRequest buildRequest(String url, String lastEventId) {
        var request = new HTTPRequest(HTTPMethod.GET, url);
        if (lastEventId != null && !lastEventId.isBlank()) {
            request.headers.put(LAST_EVENT_ID_HEADER, lastEventId);
        }
        return request;
    }
}
