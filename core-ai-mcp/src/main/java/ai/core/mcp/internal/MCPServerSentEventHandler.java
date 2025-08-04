package ai.core.mcp.internal;

import core.framework.http.HTTPMethod;
import core.framework.internal.async.VirtualThread;
import core.framework.internal.log.ActionLog;
import core.framework.internal.log.LogManager;
import core.framework.internal.web.HTTPHandlerContext;
import core.framework.internal.web.http.RateControl;
import core.framework.internal.web.request.RequestImpl;
import core.framework.internal.web.service.ErrorResponse;
import core.framework.internal.web.session.ReadOnlySession;
import core.framework.internal.web.session.SessionManager;
import core.framework.internal.web.sse.ServerSentEventHandler;
import core.framework.util.Strings;
import core.framework.web.sse.ChannelListener;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.ChannelListeners;
import org.xnio.IoUtils;
import org.xnio.channels.StreamSinkChannel;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author miller
 */
public class MCPServerSentEventHandler extends ServerSentEventHandler {
    static final long MAX_PROCESS_TIME_IN_NANO = Duration.ofSeconds(300).toNanos();
    static final HttpString HEADER_TRACE_ID = new HttpString("x-trace-id");
    private static final HttpString LAST_EVENT_ID = new HttpString("Last-Event-ID");

    private final Logger logger = LoggerFactory.getLogger(MCPServerSentEventHandler.class);
    private final LogManager logManager;
    private final HTTPHandlerContext handlerContext;
    private final SessionManager sessionManager;
    private final Map<String, MCPChannelSupport<?>> supports = new HashMap<>();

    public MCPServerSentEventHandler(LogManager logManager, SessionManager sessionManager, HTTPHandlerContext handlerContext) {
        super(logManager, sessionManager, handlerContext);
        this.logManager = logManager;
        this.handlerContext = handlerContext;
        this.sessionManager = sessionManager;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/event-stream");
        exchange.setPersistent(false);
        StreamSinkChannel sink = exchange.getResponseChannel();
        try {
            if (sink.flush()) {
                exchange.dispatch(() -> handle(exchange, sink));
            } else {
                var listener = ChannelListeners.flushingChannelListener(channel -> exchange.dispatch(() -> handle(exchange, sink)),
                    (channel, e) -> {
                        logger.warn("failed to establish sse connection, error={}", e.getMessage(), e);
                        IoUtils.safeClose(exchange.getConnection());
                    });
                sink.getWriteSetter().set(listener);
                sink.resumeWrites();
            }
        } catch (IOException e) {
            logger.warn("failed to establish sse connection, error={}", e.getMessage(), e);
            IoUtils.safeClose(exchange.getConnection());
        }
    }

    public <T> void add(HTTPMethod method, String path, Class<T> eventClass, ChannelListener<T> listener, MCPServerSentEventContextImpl<T> context) {
        var previous = supports.put(key(method.name(), path), new MCPChannelSupport<>(listener, eventClass, context));
        if (previous != null) throw new Error(Strings.format("found duplicate sse listener, method={}, path={}", method, path));
    }

    @Override
    public void shutdown() {
        logger.info("close sse connections");
        for (MCPChannelSupport<?> support : supports.values()) {
            for (var channel : support.context.all()) {
                ((MCPChannelImpl<?>) channel).shutdown();
            }
        }
    }

    void handle(HttpServerExchange exchange, StreamSinkChannel sink) {
        VirtualThread.COUNT.increase();
        long httpDelay = System.nanoTime() - exchange.getRequestStartTime();
        ActionLog actionLog = logManager.begin("=== sse connect begin ===", null);
        var request = new RequestImpl(exchange, handlerContext.requestBeanReader);
        MCPChannelImpl<Object> channel = null;
        try {
            logger.debug("httpDelay={}", httpDelay);
            actionLog.stats.put("http_delay", (double) httpDelay);

            handlerContext.requestParser.parse(request, exchange, actionLog);
            if (handlerContext.accessControl != null) handlerContext.accessControl.validate(request.clientIP());  // check ip before checking routing, return 403 asap

            actionLog.warningContext.maxProcessTimeInNano(MAX_PROCESS_TIME_IN_NANO);
            String path = request.path();
            @SuppressWarnings("unchecked")
            MCPChannelSupport<Object> support = (MCPChannelSupport<Object>) supports.get(key(request.method().name(), path));   // ServerSentEventHandler.check() ensures path exists
            actionLog.action("sse:" + path + ":connect");

            if (handlerContext.rateControl != null) {
                limitRate(handlerContext.rateControl, support, request.clientIP());
            }

            channel = new MCPChannelImpl<>(exchange, sink, support.context, support.builder, actionLog.id);
            actionLog.context("channel", channel.id);

            channel.clientIP = request.clientIP();
            String traceId = exchange.getRequestHeaders().getFirst(HEADER_TRACE_ID);    // used by frontend to trace request
            if (traceId != null) {
                actionLog.context.put("trace_id", List.of(traceId));
                channel.traceId = traceId;
            }

            sink.getWriteSetter().set(channel.writeListener);
            support.context.add(channel);
            exchange.addExchangeCompleteListener(new MCPServerSentEventCloseHandler<>(logManager, channel, support));

            channel.sendBytes(Strings.bytes("retry: 5000\n\n"));    // set browser retry to 5s

            request.session = ReadOnlySession.of(sessionManager.load(request, actionLog));
            String lastEventId = exchange.getRequestHeaders().getLast(LAST_EVENT_ID);
            if (lastEventId != null) actionLog.context("last_event_id", lastEventId);
            support.listener.onConnect(request, channel, lastEventId);
            if (!channel.groups.isEmpty()) actionLog.context("group", channel.groups.toArray()); // may join group onConnect
        } catch (Throwable e) {
            logManager.logError(e);

            if (channel != null) {
                String message = errorMessage(handlerContext.responseBeanWriter.toJSON(ErrorResponse.errorResponse(e, actionLog.id)));
                channel.sendBytes(Strings.bytes(message));
                channel.close();    // gracefully shutdown connection to make sure retry/error can be sent
            }
        } finally {
            logManager.end("=== sse connect end ===");
            VirtualThread.COUNT.decrease();
        }
    }

    private String key(String method, String path) {
        return method + ":" + path;
    }

    void limitRate(RateControl rateControl, MCPChannelSupport<Object> support, String clientIP) {
        if (support.limitRate != null) {
            String group = support.limitRate.value();
            rateControl.validateRate(group, clientIP);
        }
    }

    String errorMessage(String errorResponse) {
        return "retry: 86400000\n\n"
            + "event: error\n"
            + "data: " + errorResponse + "\n\n";
    }


}
