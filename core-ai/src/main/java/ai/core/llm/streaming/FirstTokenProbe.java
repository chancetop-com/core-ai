package ai.core.llm.streaming;

import ai.core.llm.domain.FunctionCall;

import java.util.List;

/**
 * Wraps the callback handed to the provider transport and records when the first content or reasoning
 * token arrived. It sits outside the buffering/async wrappers, so the measurement is the transport's own
 * latency rather than the time the UI happened to receive a batch.
 *
 * @author stephen
 */
public class FirstTokenProbe implements StreamingCallback {
    private final StreamingCallback delegate;
    private final long startNanos;
    private volatile long firstTokenNanos;

    public FirstTokenProbe(StreamingCallback delegate) {
        this(delegate, System.nanoTime());
    }

    public FirstTokenProbe(StreamingCallback delegate, long startNanos) {
        this.delegate = delegate;
        this.startNanos = startNanos;
    }

    /**
     * @return milliseconds from the request start to the first content/reasoning token, null when the
     * call produced no token at all (tool-only reply, cancelled before output, transport failure)
     */
    public Long ttftMillis() {
        long first = firstTokenNanos;
        if (first == 0) return null;
        return Math.max(0, (first - startNanos) / 1_000_000);
    }

    private void markFirstToken() {
        if (firstTokenNanos == 0) firstTokenNanos = System.nanoTime();
    }

    @Override
    public void onChunk(String chunk) {
        markFirstToken();
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
        delegate.reset();
    }
}
