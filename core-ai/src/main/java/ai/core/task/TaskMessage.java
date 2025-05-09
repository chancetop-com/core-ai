package ai.core.task;

import ai.core.task.parts.TextPart;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class TaskMessage {
    public static TaskMessage of(TaskRoleType role, String text) {
        var message = new TaskMessage();
        message.setRole(role);
        var textPart = new TextPart(text);
        message.setParts(List.of(textPart));
        return message;
    }

    private TaskRoleType role;
    private List<Part<?>> parts;
    private Map<String, String> metadata;

    public TaskRoleType getRole() {
        return role;
    }

    public void setRole(TaskRoleType role) {
        this.role = role;
    }

    public List<Part<?>> getParts() {
        return parts;
    }

    public TextPart getTextPart() {
        return (TextPart) parts.stream()
                .filter(part -> part instanceof TextPart)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No TextPart found"));
    }

    public void setParts(List<Part<?>> parts) {
        this.parts = parts;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }
}
