package ai.core.llm.streaming;

/**
 * A streaming callback that also observes the transport's first content or reasoning token.
 * Providers measure the latency from the request to that token (TTFT) and hand it to the callback,
 * so a host can display the wait without re-timing the buffered UI stream.
 *
 * @author stephen
 */
public interface FirstTokenListener {
    void onFirstToken(long ttftMs);
}
