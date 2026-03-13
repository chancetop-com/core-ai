package ai.core.agent;

import java.io.Serial;

/**
 * @author stephen
 */
public class MaxTurnsExceededException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 3827461029384756102L;

    public final int maxTurns;

    public MaxTurnsExceededException(int maxTurns) {
        super("agent exceeded max turns limit: " + maxTurns);
        this.maxTurns = maxTurns;
    }
}
