package ai.core.server.workflow;

import java.util.List;

/**
 * Thrown when publish-time validation rejects a workflow graph. Carries every error so the editor can show
 * them all at once.
 *
 * @author Xander
 */
public class WorkflowValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient List<String> errors;

    public WorkflowValidationException(List<String> errors) {
        super("workflow validation failed: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
