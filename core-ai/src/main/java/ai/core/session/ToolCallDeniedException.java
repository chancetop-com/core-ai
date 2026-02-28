package ai.core.session;

import java.io.Serial;

/**
 * @author stephen
 */
public class ToolCallDeniedException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 5765908037036453668L;

    public ToolCallDeniedException(String toolName) {
        super("denied: " + toolName);
    }
}
