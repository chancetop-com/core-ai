package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.RoleType;

public class TodoReminderLifecycle extends AbstractLifecycle {
    static final String WRITE_TODOS_TOOL_NAME = "write_todos";
    static final int DEFAULT_REMINDER_THRESHOLD = 3;
    static final String REMINDER_MESSAGE = """

            <system-reminder>
            The task tools haven't been used recently. \
            If you're working on tasks that would benefit from tracking progress, \
            consider using write_todos to update task status \
            (set to in_progress when starting, completed when done). \
            Also consider cleaning up the task list if it has become stale. \
            Only use these if relevant to the current work. \
            This is just a gentle reminder - ignore if not applicable.
            </system-reminder>""";

    private final int reminderThreshold;

    public TodoReminderLifecycle() {
        this(DEFAULT_REMINDER_THRESHOLD);
    }

    public TodoReminderLifecycle(int reminderThreshold) {
        this.reminderThreshold = reminderThreshold;
    }

    @Override
    public void beforeModel(CompletionRequest request, ExecutionContext context) {
        if (request == null || request.messages == null || request.messages.isEmpty()) return;
        if (!hasTodosInContext(request)) return;

        int toolCallsSinceLastTodos = countToolCallsSinceLastWriteTodos(request);
        if (toolCallsSinceLastTodos >= reminderThreshold) {
            appendReminder(request);
        }
    }

    boolean hasTodosInContext(CompletionRequest request) {
        return request.messages.stream()
                .anyMatch(m -> RoleType.TOOL.equals(m.role)
                        && WRITE_TODOS_TOOL_NAME.equals(m.name));
    }

    int countToolCallsSinceLastWriteTodos(CompletionRequest request) {
        int count = 0;
        for (int i = request.messages.size() - 1; i >= 0; i--) {
            var msg = request.messages.get(i);
            if (RoleType.TOOL.equals(msg.role)) {
                if (WRITE_TODOS_TOOL_NAME.equals(msg.name)) {
                    return count;
                }
                count++;
            }
        }
        return count;
    }

    void appendReminder(CompletionRequest request) {
        for (int i = request.messages.size() - 1; i >= 0; i--) {
            var msg = request.messages.get(i);
            if (RoleType.TOOL.equals(msg.role)) {
                var currentText = msg.getTextContent();
                if (currentText != null && currentText.contains(REMINDER_MESSAGE)) return;
                msg.content.getFirst().text = (currentText == null ? "" : currentText) + REMINDER_MESSAGE;
                return;
            }
        }
    }
}
