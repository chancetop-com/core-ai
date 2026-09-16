package ai.core.context;

/**
 * @author xander
 */
public interface CompressionListener {
    /**
     * @param beforeCount messages in the conversation before compression
     * @param afterCount  messages being summarized when the compression starts,
     *                    messages in the conversation after it completed
     * @param completed   false when the summarization call starts, true when it finished
     */
    void onCompression(int beforeCount, int afterCount, boolean completed);

    /**
     * Reports a compression that started and was then given up on, so callers can replace the
     * progress they showed for it. The conversation is left exactly as it was.
     *
     * @param beforeCount messages in the conversation, unchanged
     * @param reason      why the conversation was kept as it was
     */
    default void onCompressionSkipped(int beforeCount, String reason) {
    }
}
