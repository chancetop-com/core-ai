package ai.core.agent.streaming;

import ai.core.llm.domain.FunctionCall;

import java.util.List;

/**
 * @author lim chen
 */
public class BufferedStreamingCallback implements StreamingCallback {
    private final StreamingCallback delegate;
    private final int bufferSize;
    private final StringBuilder chunkBuffer = new StringBuilder();
    private final StringBuilder reasoningBuffer = new StringBuilder();

    public BufferedStreamingCallback(StreamingCallback delegate, int bufferSize) {
        this.delegate = delegate;
        this.bufferSize = bufferSize;
    }

    @Override
    public void onChunk(String chunk) {
        chunkBuffer.append(chunk);
        if (chunkBuffer.length() >= bufferSize) {
            flushChunkBuffer();
        }
    }

    @Override
    public void onReasoningChunk(String chunk) {
        reasoningBuffer.append(chunk);
        if (reasoningBuffer.length() >= bufferSize) {
            flushReasoningBuffer();
        }
    }

    @Override
    public void onTool(List<FunctionCall> functionCalls) {
        flushChunkBuffer();
        delegate.onTool(functionCalls);
    }

    @Override
    public void onToolComplete(List<FunctionCall> functionCalls) {
        flushChunkBuffer();
        delegate.onToolComplete(functionCalls);
    }

    @Override
    public void onReasoningComplete(String reasoning) {
        flushReasoningBuffer();
        delegate.onReasoningComplete(reasoning);
    }

    @Override
    public void onComplete() {
        flushChunkBuffer();
        flushReasoningBuffer();
        delegate.onComplete();
    }

    @Override
    public void onError(Throwable error) {
        flushChunkBuffer();
        flushReasoningBuffer();
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
        chunkBuffer.setLength(0);
        reasoningBuffer.setLength(0);
        delegate.reset();
    }

    private void flushChunkBuffer() {
        if (chunkBuffer.isEmpty()) return;
        delegate.onChunk(chunkBuffer.toString());
        chunkBuffer.setLength(0);
    }

    private void flushReasoningBuffer() {
        if (reasoningBuffer.isEmpty()) return;
        delegate.onReasoningChunk(reasoningBuffer.toString());
        reasoningBuffer.setLength(0);
    }
}
