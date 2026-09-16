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
}
