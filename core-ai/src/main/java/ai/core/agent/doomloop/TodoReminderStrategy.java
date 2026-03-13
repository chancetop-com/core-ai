package ai.core.agent.doomloop;

import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.RoleType;

public class TodoReminderStrategy implements DoomLoopStrategy {
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

    public TodoReminderStrategy() {
        this(DEFAULT_REMINDER_THRESHOLD);
    }

    public TodoReminderStrategy(int reminderThreshold) {
        this.reminderThreshold = reminderThreshold;
    }

    @Override
    public boolean detect(CompletionRequest request, ExecutionContext context) {
        if (!hasTodosInContext(request)) return false;
        return countToolCallsSinceLastWriteTodos(request) >= reminderThreshold;
    }

    @Override
    public String warningMessage() {
        return REMINDER_MESSAGE;
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
                if (WRITE_TODOS_TOOL_NAME.equals(msg.name)) return count;
                count++;
            }
        }
        return count;
    }
}
