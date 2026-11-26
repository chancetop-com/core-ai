package ai.core.llm.streaming;

import ai.core.llm.domain.FunctionCall;

import java.util.List;
import java.util.function.LongConsumer;

/**
 * Wraps the callback handed to the provider transport and records when the first content or reasoning
 * token arrived. It sits outside the buffering/async wrappers, so the measurement is the transport's own
 * latency rather than the time the UI happened to receive a batch.
 * <p>
 * Two timestamps are kept: the first token of any kind (what the user waits for, and what the live
 * session view shows) and the first answer-content token. Reasoning models can stream thinking for a
 * long time before the answer starts, so the decode window of the answer only begins at the second one.
 *
 * @author stephen
 */
public class FirstTokenProbe implements StreamingCallback {
    private final StreamingCallback delegate;
    private final long startNanos;
    private final LongConsumer firstTokenSink;
    private volatile long firstTokenNanos;
    private volatile long firstContentNanos;

    public FirstTokenProbe(StreamingCallback delegate) {
        this(delegate, System.nanoTime(), null);
    }

    public FirstTokenProbe(StreamingCallback delegate, LongConsumer firstTokenSink) {
        this(delegate, System.nanoTime(), firstTokenSink);
    }

    public FirstTokenProbe(StreamingCallback delegate, long startNanos) {
        this(delegate, startNanos, null);
    }

    public FirstTokenProbe(StreamingCallback delegate, long startNanos, LongConsumer firstTokenSink) {
        this.delegate = delegate;
        this.startNanos = startNanos;
        this.firstTokenSink = firstTokenSink;
    }

    /**
     * @return milliseconds from the request start to the first content/reasoning token, null when the
     * call produced no token at all (tool-only reply, cancelled before output, transport failure)
     */
    public Long ttftMillis() {
        return millisSince(firstTokenNanos);
    }

    /**
     * @return milliseconds from the request start to the first answer-content token, null when the
     * reply carried no answer text (reasoning-only or tool-only reply)
     */
    public Long firstContentMillis() {
        return millisSince(firstContentNanos);
    }

    private Long millisSince(long nanos) {
        if (nanos == 0) return null;
        return Math.max(0, (nanos - startNanos) / 1_000_000);
    }

    private void markFirstToken() {
        if (firstTokenNanos != 0) return;
        long first = System.nanoTime();
        synchronized (this) {
            if (firstTokenNanos != 0) return;
            firstTokenNanos = first;
        }
        if (firstTokenSink != null) {
            firstTokenSink.accept(Math.max(0, (first - startNanos) / 1_000_000));
        }
    }

    private void markFirstContent() {
        if (firstContentNanos != 0) return;
        synchronized (this) {
            if (firstContentNanos == 0) firstContentNanos = System.nanoTime();
        }
    }

    @Override
    public void onChunk(String chunk) {
        markFirstToken();
        markFirstContent();
        delegate.onChunk(chunk);
    }

    @Override
    public void onReasoningChunk(String chunk) {
        markFirstToken();
        delegate.onReasoningChunk(chunk);
    }

    @Override
    public void onTool(List<FunctionCall> functionCalls) {
        delegate.onTool(functionCalls);
    }

    @Override
    public void onToolComplete(List<FunctionCall> functionCalls) {
        delegate.onToolComplete(functionCalls);
    }

    @Override
    public void onReasoningComplete(String reasoning) {
        delegate.onReasoningComplete(reasoning);
    }

    @Override
    public void onComplete() {
        delegate.onComplete();
    }

    @Override
    public void onError(Throwable error) {
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
    public void onRawData(String sseData) {
        delegate.onRawData(sseData);
    }

    @Override
    public void onOutput(String source, String callId, String chunk) {
        delegate.onOutput(source, callId, chunk);
    }

    @Override
    public void reset() {
        firstTokenNanos = 0;
        firstContentNanos = 0;
        delegate.reset();
    }
}
