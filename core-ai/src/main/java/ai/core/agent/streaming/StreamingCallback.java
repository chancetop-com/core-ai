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

    default void onReasoningChunk(String chunk) {

    }

    default void onReasoningComplete(String reasoning) {

    }

    default void onComplete() {
    }

    default void onError(Throwable error) {

    }
}
