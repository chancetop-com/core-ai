package ai.core.agent.doomloop;

import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.RoleType;
import ai.core.tool.tools.WriteTodoTaskTool;

import java.util.Set;

/**
 * Doom-loop detection for Task V2 tools.
 * Injects a reminder when {@code task_create} or {@code task_update} haven't been called
 * within the last N tool invocations, prompting the model to update task status.
 *
 * @author lim chen
 */
public class TaskReminderStrategy implements DoomLoopStrategy {
    static final Set<String> TASK_TOOL_NAMES = Set.of(
            WriteTodoTaskTool.TOOL_NAME_CREATE,
            WriteTodoTaskTool.TOOL_NAME_UPDATE
    );
    static final int DEFAULT_REMINDER_THRESHOLD = 10;
    static final String REMINDER_MESSAGE = """
            <system-reminder>
            The task tools haven't been used recently.
            If you're working on tasks that would benefit from tracking progress,
            consider using task_update to update task status
            (set to in_progress when starting, completed when done).
            Also consider using task_list to review the current state,
            or task_create to add new tasks if scope has changed.
            This is just a gentle reminder - ignore if not applicable.
            </system-reminder>
            """;

    private final int reminderThreshold;

    public TaskReminderStrategy() {
        this(DEFAULT_REMINDER_THRESHOLD);
    }

    public TaskReminderStrategy(int reminderThreshold) {
        this.reminderThreshold = reminderThreshold;
    }

    @Override
    public boolean detect(CompletionRequest request, ExecutionContext context) {
        if (!hasTaskToolsInContext(request)) return false;
        return countToolCallsSinceLastTaskTool(request) >= reminderThreshold;
    }

    @Override
    public String warningMessage() {
        return REMINDER_MESSAGE;
    }

    boolean hasTaskToolsInContext(CompletionRequest request) {
        return request.messages.stream()
                .anyMatch(m -> RoleType.TOOL.equals(m.role)
                        && m.name != null
                        && TASK_TOOL_NAMES.contains(m.name));
    }

    int countToolCallsSinceLastTaskTool(CompletionRequest request) {
        int count = 0;
        for (int i = request.messages.size() - 1; i >= 0; i--) {
            var msg = request.messages.get(i);
            if (RoleType.TOOL.equals(msg.role)) {
                if (msg.name != null && TASK_TOOL_NAMES.contains(msg.name)) return count;
                count++;
            }
        }
        return count;
    }
}
