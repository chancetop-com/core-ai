package ai.core.llm.providers;

import java.io.Serial;

/**
 * The upstream rejected the request because the prompt exceeds the model's context window. Distinct
 * from a generic 400 so the agent can compress and retry instead of failing the turn.
 *
 * @author stephen
 */
public class LLMContextWindowExceededException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 9182736450918273645L;

    public LLMContextWindowExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
