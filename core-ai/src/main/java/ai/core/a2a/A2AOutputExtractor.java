package ai.core.a2a;

import ai.core.api.a2a.Artifact;
import ai.core.api.a2a.Message;
import ai.core.api.a2a.Part;
import ai.core.api.a2a.Task;
import ai.core.api.a2a.TaskState;

import java.util.List;

/**
 * Extracts the compact text result that should be returned to a calling agent.
 *
 * @author xander
 */
public class A2AOutputExtractor {
    private final int maxOutputChars;

    public A2AOutputExtractor(int maxOutputChars) {
        this.maxOutputChars = maxOutputChars;
    }

    public Output fromInvocation(A2AInvocationResult invocation) {
        var output = new Output();
        if (invocation == null) return output;
        if (invocation.task != null) {
            applyTask(output, invocation.task);
        }
        if (invocation.message != null && isBlank(output.text)) {
            output.text = textOf(invocation.message);
        }
        output.applyLimit(maxOutputChars);
        return output;
    }

    public Output fromStreamEvents(List<A2AStreamEvent> events) {
        var output = new Output();
        var artifactText = new StringBuilder();
        var messageText = new StringBuilder();
        var taskText = new StringBuilder();
        var statusText = new StringBuilder();
        for (var event : events) {
            applyEvent(output, event, artifactText, messageText, taskText, statusText);
        }
        var text = chooseText(artifactText, taskText, messageText, statusText);
        if (!isBlank(text)) output.text = text;
        output.applyLimit(maxOutputChars);
        return output;
    }

    private void applyEvent(Output output, A2AStreamEvent event, StringBuilder artifactText,
                            StringBuilder messageText, StringBuilder taskText, StringBuilder statusText) {
        if (event == null) return;
        if (event.error != null) {
            output.error = event.error.message;
        }
        if (event.task != null) {
            applyTask(output, event.task);
            var taskEventText = textOf(event.task.artifacts);
            if (isBlank(taskEventText) && event.task.status != null) {
                taskEventText = textOf(event.task.status.message);
            }
            appendText(taskText, taskEventText);
        }
        if (event.message != null) {
            appendText(messageText, textOf(event.message));
        }
        if (event.artifactUpdate != null) {
            output.taskId = valueOr(output.taskId, event.artifactUpdate.taskId);
            output.contextId = valueOr(output.contextId, event.artifactUpdate.contextId);
            appendText(artifactText, textOf(event.artifactUpdate.artifact));
        }
        if (event.statusUpdate != null) {
            output.taskId = valueOr(output.taskId, event.statusUpdate.taskId);
            output.contextId = valueOr(output.contextId, event.statusUpdate.contextId);
            if (event.statusUpdate.status != null) {
                output.state = event.statusUpdate.status.state;
                var statusMessageText = textOf(event.statusUpdate.status.message);
                appendText(statusText, statusMessageText);
                output.statusText = valueOr(statusMessageText, output.statusText);
            }
        }
    }

    private void applyTask(Output output, Task task) {
        output.taskId = valueOr(output.taskId, task.id);
        output.contextId = valueOr(output.contextId, task.contextId);
        if (task.status != null) output.state = task.status.state;
        if (task.status != null) {
            output.statusText = valueOr(textOf(task.status.message), output.statusText);
        }
        var text = textOf(task.artifacts);
        if (isBlank(text) && task.status != null) text = output.statusText;
        if (!isBlank(text)) output.text = text;
    }

    private String chooseText(StringBuilder artifactText, StringBuilder taskText,
                              StringBuilder messageText, StringBuilder statusText) {
        if (!artifactText.isEmpty()) return artifactText.toString();
        if (!taskText.isEmpty()) return taskText.toString();
        if (!messageText.isEmpty()) return messageText.toString();
        if (!statusText.isEmpty()) return statusText.toString();
        return "";
    }

    private String textOf(List<Artifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) return "";
        var builder = new StringBuilder();
        for (var artifact : artifacts) {
            appendText(builder, textOf(artifact));
        }
        return builder.toString();
    }

    private String textOf(Artifact artifact) {
        if (artifact == null || artifact.parts == null || artifact.parts.isEmpty()) return "";
        var builder = new StringBuilder();
        for (var part : artifact.parts) {
            appendText(builder, textOf(part));
        }
        return builder.toString();
    }

    private String textOf(Message message) {
        if (message == null || message.parts == null || message.parts.isEmpty()) return "";
        var builder = new StringBuilder();
        for (var part : message.parts) {
            appendText(builder, textOf(part));
        }
        return builder.toString();
    }

    private String textOf(Part part) {
        if (part == null || part.text == null) return "";
        return part.text;
    }

    private void appendText(StringBuilder builder, String text) {
        if (isBlank(text)) return;
        if (!builder.isEmpty()) builder.append('\n');
        builder.append(text);
    }

    private String valueOr(String current, String candidate) {
        if (current != null && !current.isBlank()) return current;
        return candidate;
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    public static class Output {
        public String text = "";
        public String taskId;
        public String contextId;
        public TaskState state;
        public String statusText;
        public String error;
        public boolean truncated;

        private void applyLimit(int maxOutputChars) {
            if (maxOutputChars <= 0 || text == null || text.length() <= maxOutputChars) return;
            text = text.substring(0, maxOutputChars) + "\n[truncated]";
            truncated = true;
        }
    }
}
