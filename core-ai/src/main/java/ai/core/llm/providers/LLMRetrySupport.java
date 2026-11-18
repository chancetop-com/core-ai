package ai.core.llm.providers;

import ai.core.agent.CancellationException;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.streaming.StreamingCallback;
import core.framework.http.HTTPClientException;
import core.framework.util.Strings;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Retry policy for the chat/completions SSE transport, shared by the chat and responses bridges.
 * <p>
 * A retry is only safe while the attempt delivered nothing downstream, and only helps when the
 * failure is transport-level: a restarting core-ai-server closes the connection (or returns 502/503)
 * without sending data. Request rejections and in-stream upstream errors are deterministic.
 *
 * @author stephen
 */
final class LLMRetrySupport {
    static final int MAX_RETRIES = 3;
    static final long RETRY_WAIT_MILLIS = TimeUnit.SECONDS.toMillis(3);
    // a rolling restart of core-ai-server can keep the CLI proxy down for tens of seconds; a turn
    // should wait it out instead of failing with an opaque transport error on the first attempt
    static final long SERVER_RETRY_INITIAL_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(1);
    static final long SERVER_RETRY_MAX_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(10);
    static final double SERVER_RETRY_JITTER = 0.2;
    // an error body (an ingress html page, a gateway json error) can be large; the message is
    // surfaced to the user, the full cause stays on the exception
    static final int MAX_ERROR_TEXT = 400;
    private static final String SERVER_PROXY_PATH = "/api/cli/v1";
    private static final String SERVER_PROXY_LEGACY_PATH = "/api/litellm/v1";
    private static final Pattern STATUS_CODE = Pattern.compile("statusCode=(\\d{3})");

    static boolean isServerProxyEndpoint(String uri) {
        return uri != null && (uri.contains(SERVER_PROXY_PATH) || uri.contains(SERVER_PROXY_LEGACY_PATH));
    }

    static boolean retryable(RuntimeException error) {
        if (error instanceof LLMStreamException || error instanceof CancellationException) return false;
        if (error instanceof IncompleteStreamException) return true;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HTTPClientException httpError) {
                if ("INVALID_URL".equals(httpError.errorCode())) return false;
                var statusCode = statusCode(httpError.getMessage());
                if (statusCode == null) return true;
                return statusCode == 200 || statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500;
            }
            if (cause instanceof SocketTimeoutException || cause instanceof InterruptedIOException) return true;
            if (cause instanceof IOException) return true;
        }
        return false;
    }

    // package-private for tests: exponential backoff capped at SERVER_RETRY_MAX_DELAY_MILLIS
    static long retryDelayMillis(int failures, double jitter) {
        int shift = Math.min(Math.max(failures - 1, 0), 10);
        long base = Math.min(SERVER_RETRY_INITIAL_DELAY_MILLIS << shift, SERVER_RETRY_MAX_DELAY_MILLIS);
        return Math.max(1, (long) (base * (1 + jitter)));
    }

    static boolean sleep(long millis, StreamingCallback callback) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!callback.isCancelled()) {
            long remainingMillis = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMillis <= 0) return true;
            try {
                // wake up periodically so a cancelled turn does not wait out the whole backoff
                Thread.sleep(Math.min(remainingMillis, 200L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    static RuntimeException incompleteStream(String uri, boolean serverProxy, String phase) {
        return incompleteStream(uri, serverProxy, phase, null);
    }

    static RuntimeException incompleteStream(String uri, boolean serverProxy, String phase, Throwable cause) {
        var message = endpointLabel(uri, serverProxy) + " closed the connection " + phase + " (it may be restarting)";
        return new IncompleteStreamException(message, cause);
    }

    static RuntimeException interruptedStream(String uri, boolean serverProxy, RuntimeException cause) {
        var message = endpointLabel(uri, serverProxy)
                + " connection lost after partial output; the request was not retried to avoid duplicate output: "
                + errorMessage(cause);
        return new RuntimeException(message, cause);
    }

    static RuntimeException unavailable(String uri, boolean serverProxy, int failures, long firstFailureNanos, RuntimeException lastError) {
        long retriedSeconds = Math.max(0, (System.nanoTime() - firstFailureNanos) / 1_000_000_000L);
        var reason = lastError == null ? "no data returned" : errorMessage(lastError);
        var message = endpointLabel(uri, serverProxy) + " is unavailable: " + reason
                + " (gave up after " + failures + " attempt(s) over " + retriedSeconds + "s)";
        return new LLMUnavailableException(message, lastError);
    }

    static String errorMessage(RuntimeException error) {
        var message = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        return message.length() > MAX_ERROR_TEXT ? message.substring(0, MAX_ERROR_TEXT - 3) + "..." : message;
    }

    private static Integer statusCode(String message) {
        if (message == null) return null;
        var matcher = STATUS_CODE.matcher(message);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private static String endpointLabel(String uri, boolean serverProxy) {
        String host = host(uri);
        if (serverProxy) return host == null ? "core-ai-server" : "core-ai-server (" + host + ")";
        return host == null ? "LLM endpoint" : host;
    }

    private static String host(String uri) {
        try {
            return uri == null ? null : URI.create(uri).getHost();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private LLMRetrySupport() {
    }

    /**
     * Records whether an attempt already delivered anything downstream. A retry is only safe while
     * the caller has seen nothing: repeating after a chunk or a tool call would duplicate output.
     * Passthrough requests forward raw SSE frames, so those count as delivery too.
     */
    static final class OutputTrackingCallback implements StreamingCallback {
        private final StreamingCallback delegate;
        private final boolean rawDataVisible;
        private boolean outputEmitted;

        OutputTrackingCallback(StreamingCallback delegate, boolean rawDataVisible) {
            this.delegate = delegate;
            this.rawDataVisible = rawDataVisible;
        }

        boolean outputEmitted() {
            return outputEmitted;
        }

        @Override
        public void onChunk(String chunk) {
            if (StringUtils.isNotEmpty(chunk)) outputEmitted = true;
            delegate.onChunk(chunk);
        }

        @Override
        public void onReasoningChunk(String chunk) {
            if (StringUtils.isNotEmpty(chunk)) outputEmitted = true;
            delegate.onReasoningChunk(chunk);
        }

        @Override
        public void onTool(List<FunctionCall> functionCalls) {
            if (functionCalls != null && !functionCalls.isEmpty()) outputEmitted = true;
            delegate.onTool(functionCalls);
        }

        @Override
        public void onToolComplete(List<FunctionCall> functionCalls) {
            outputEmitted = true;
            delegate.onToolComplete(functionCalls);
        }

        @Override
        public void onReasoningComplete(String reasoning) {
            if (!Strings.isBlank(reasoning)) outputEmitted = true;
            delegate.onReasoningComplete(reasoning);
        }

        @Override
        public void onRawData(String sseData) {
            if (rawDataVisible && StringUtils.isNotEmpty(sseData)) outputEmitted = true;
            delegate.onRawData(sseData);
        }

        @Override
        public void onOutput(String source, String callId, String chunk) {
            if (StringUtils.isNotEmpty(chunk)) outputEmitted = true;
            delegate.onOutput(source, callId, chunk);
        }

        @Override
        public void onComplete() {
            outputEmitted = true;
            delegate.onComplete();
        }

        @Override
        public void onError(Throwable error) {
            outputEmitted = true;
            delegate.onError(error);
        }

        @Override
        public void setActiveConnection(AutoCloseable connection) {
            delegate.setActiveConnection(connection);
        }

        @Override
        public void cancelConnection() {
            delegate.cancelConnection();
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public void reset() {
            delegate.reset();
        }
    }

    static final class IncompleteStreamException extends RuntimeException {
        private static final long serialVersionUID = 6192837465019283746L;

        private IncompleteStreamException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }
}
