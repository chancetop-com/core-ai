package ai.core.agent.streaming;

import ai.core.llm.domain.FunctionCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @author lim chen
 */
public class AsyncStreamingCallback implements StreamingCallback {
    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncStreamingCallback.class);
    private static final long DRAIN_TIMEOUT_SECONDS = 30;

    private final StreamingCallback delegate;
    private volatile ExecutorService executor;

    public AsyncStreamingCallback(StreamingCallback delegate) {
        this.delegate = delegate;
        this.executor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void onChunk(String chunk) {
        executor.execute(() -> delegate.onChunk(chunk));
    }

    @Override
    public void onReasoningChunk(String chunk) {
        executor.execute(() -> delegate.onReasoningChunk(chunk));
    }

    @Override
    public void onTool(List<FunctionCall> functionCalls) {
        executor.execute(() -> delegate.onTool(functionCalls));
    }

    @Override
    public void onToolComplete(List<FunctionCall> functionCalls) {
        executor.execute(() -> delegate.onToolComplete(functionCalls));
    }

    @Override
    public void onReasoningComplete(String reasoning) {
        executor.execute(() -> delegate.onReasoningComplete(reasoning));
    }

    @Override
    public void onComplete() {
        drainAndRun(delegate::onComplete);
    }

    @Override
    public void onError(Throwable error) {
        drainAndRun(() -> delegate.onError(error));
    }

    @Override
    public void setActiveConnection(AutoCloseable connection) {
        delegate.setActiveConnection(connection);
    }

    @Override
    public void cancelConnection() {
        delegate.cancelConnection();
        shutdownExecutor();
    }

    @Override
    public boolean isCancelled() {
        return delegate.isCancelled();
    }

    @Override
    public void reset() {
        shutdownExecutor();
        executor = Executors.newSingleThreadExecutor();
        delegate.reset();
    }

    private void drainAndRun(Runnable finalAction) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("async streaming callback drain timed out");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        finalAction.run();
        executor = Executors.newSingleThreadExecutor();
    }

    private void shutdownExecutor() {
        executor.shutdownNow();
    }
}
