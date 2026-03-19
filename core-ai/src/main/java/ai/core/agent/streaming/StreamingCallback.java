package ai.core.agent.streaming;

import ai.core.llm.domain.FunctionCall;

import java.util.List;

/**
 * @author stephen
 */
public interface StreamingCallback {

    void onChunk(String chunk);

    default void onReasoningChunk(String chunk) {

    }

    default void onTool(List<FunctionCall> functionCalls) {

    }

    default void onToolComplete(List<FunctionCall> functionCalls) {

    }

    default void onReasoningComplete(String reasoning) {

    }

    default void onComplete() {
    }

    default void onError(Throwable error) {

    }

    default void setActiveConnection(AutoCloseable connection) {
    }

    default void cancelConnection() {
    }

    default boolean isCancelled() {
        return false;
    }

    default void reset() {
    }
}
