package ai.core.memory.compression;

/**
 * Strategy interface for compressing memory content.
 * Different implementations can use various approaches (LLM summarization, extraction, etc.)
 *
 * @author Xander
 */
public interface CompressionStrategy {

    /**
     * Compress the given content into a shorter representation.
     *
     * @param content the original content to compress
     * @return compressed/summarized content
     */
    String compress(String content);

    /**
     * Compress new content while preserving existing summary context.
     *
     * @param existingSummary the current summary (may be empty)
     * @param newContent new content to incorporate
     * @return updated compressed summary
     */
    String compressIncremental(String existingSummary, String newContent);
}
