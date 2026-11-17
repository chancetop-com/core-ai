package ai.core.llm.providers;

import java.io.Serial;

/**
 * The LLM endpoint stayed unreachable for the whole retry window (connection failure, gateway
 * restart, or a stream that closed before any usable data). Distinct from an upstream rejection
 * ({@link LLMStreamException}): the same request can succeed once the endpoint is back.
 *
 * @author stephen
 */
public class LLMUnavailableException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 3928174650192837465L;

    public LLMUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
