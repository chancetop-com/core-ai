package ai.core.cli.hub.skill;

import java.io.Serial;

/**
 * Raised when an install would overwrite a local copy the user owns or has modified.
 * Callers that can ask the user again retry with {@code force}; the message is the
 * CLI-facing instruction (unchanged from the IllegalStateException it replaces).
 *
 * @author stephen
 */
public class SkillInstallConflictException extends IllegalStateException {
    @Serial
    private static final long serialVersionUID = 1L;

    public SkillInstallConflictException(String message) {
        super(message);
    }
}
