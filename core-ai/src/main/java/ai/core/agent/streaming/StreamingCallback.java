package ai.core.agent.streaming;

/**
 * @author stephen
 */
public interface StreamingCallback {
    default void onToolStart(String toolName) {
    }

    default void onToolResult(String toolName, String result) {
    }

    void onChunk(String chunk);

    void onComplete();

    void onError(Throwable error);
}
