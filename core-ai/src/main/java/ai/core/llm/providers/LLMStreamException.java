package ai.core.llm.providers;

import java.io.Serial;

/**
 * Thrown when an OpenAI-compatible upstream reports a failure as an in-stream error event
 * (e.g. {@code {"error":{...}}} or {@code {"type":"error",...}}) instead of an HTTP error status.
 * The stream carries a definitive upstream rejection, so it must not be retried.
 *
 * @author stephen
 */
public class LLMStreamException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 7182937465019283746L;

    public LLMStreamException(String message) {
        super(message);
    }
}
