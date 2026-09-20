package ai.core.context;

/**
 * @author stephen
 */
public interface CompressionListener {
    /**
     * Reports one compression step: {@code STARTED} when the summarization call is about to run, then
     * either {@code COMPLETED} when the conversation was replaced by its summary or {@code SKIPPED}
     * when it was left exactly as it was.
     *
     * @param report the message counts, the context usage and, for a skipped compression, the reason
     */
    void onCompression(CompressionReport report);
}
