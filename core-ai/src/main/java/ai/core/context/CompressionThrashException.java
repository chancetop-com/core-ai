package ai.core.context;

/**
 * Thrown when an agent keeps re-executing the same tool calls after context compression wiped their results:
 * the model's context window cannot hold the task, so compressing again will not make progress.
 *
 * @author stephen
 */
public class CompressionThrashException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CompressionThrashException(String message) {
        super(message);
    }
}
