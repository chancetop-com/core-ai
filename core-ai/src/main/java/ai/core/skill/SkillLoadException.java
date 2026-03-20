package ai.core.skill;

/**
 * @author stephen
 */
@SuppressWarnings("serial")
public class SkillLoadException extends RuntimeException {
    public SkillLoadException(String message) {
        super(message);
    }

    public SkillLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
