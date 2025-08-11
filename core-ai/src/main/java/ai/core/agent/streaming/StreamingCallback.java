package ai.core.agent.streaming;

/**
 * @author stephen
 */
public interface StreamingCallback {
    void onChunk(String chunk);

    void onComplete();

    void onError(Throwable error);
}
