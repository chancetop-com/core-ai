package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.schedule.ScheduledTask;
import ai.core.schedule.ScheduledTaskStore;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.util.Strings;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Creates, lists and deletes session-bound scheduled tasks. When a task fires, its
 * input is injected into the originating session as a user message (with a
 * "[Scheduled task: name]" prefix) so the agent continues executing with the full
 * session context — no new agent run is started.
 *
 * @author stephen
 */
public final class ScheduledTaskTool extends ToolCall {
    public static final String TOOL_NAME = "scheduled_task";
    private static final String PREFIX = "[Scheduled task: %s]";

    private static final String TOOL_DESC = buildToolDescription();

    private static String buildToolDescription() {
        return """
                Create, list or delete a scheduled task that fires back into the current session.

                When a scheduled task fires, its input is injected into this session as a user message
                prefixed with "[Scheduled task: <name>]", and the agent continues executing with the
                full session context. The task only fires while the session is active (CLI: while the
                session is open; server: the scheduler ticks every minute); missed occurrences are not
                caught up.

                Parameters:
                - action (required): "create" to schedule a new task, "list" to list this session's
                  tasks, "delete" to remove one.
                - cron_expression (required for create): 5-field cron, e.g. "0 9 * * *" for every day
                  at 09:00, "*/30 * * * *" for every 30 minutes.
                - input (required for create): the message injected into the session when the task fires.
                - name (optional for create): a short task name used in the injected message prefix.
                - timezone (optional for create): IANA zone id, e.g. "Asia/Shanghai" or "UTC". Defaults
                  to the local timezone.
                - id (required for delete): the task id returned by create or list.
                """.stripIndent();
    }

    public static ScheduledTaskTool.Builder builder() {
        return new ScheduledTaskTool.Builder();
    }

    public static ScheduledTaskTool.Builder builder(ScheduledTaskStore store) {
        return new ScheduledTaskTool.Builder().store(store);
    }

    /**
     * Builds the message injected into the session when the task fires.
     */
    public static String buildTriggerMessage(ScheduledTask task) {
        var prefix = PREFIX.formatted(task.name);
        return task.input == null || task.input.isBlank() ? prefix : prefix + " " + task.input;
    }

    private final ScheduledTaskStore store;

    private ScheduledTaskTool(ScheduledTaskStore store) {
        this.store = store;
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return ToolCallResult.failed(TOOL_NAME + " requires execution context");
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        try {
            if (store == null) return ToolCallResult.failed("scheduled task store is not configured");
            var args = parseArguments(arguments);
            var action = getStringValue(args, "action");
            if (action == null) return ToolCallResult.failed("action is required (create | list | delete)");
            return switch (action) {
                case "create" -> create(args, context);
                case "list" -> list(context);
                case "delete" -> delete(args, context);
                default -> ToolCallResult.failed("invalid action: " + action + " (expected create | list | delete)");
            };
        } catch (Exception e) {
            return ToolCallResult.failed(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private ToolCallResult create(Map<String, Object> args, ExecutionContext context) {
        var cronExpression = getStringValue(args, "cron_expression");
        if (Strings.isBlank(cronExpression)) return ToolCallResult.failed("cron_expression is required for create");
        var input = getStringValue(args, "input");
        if (Strings.isBlank(input)) return ToolCallResult.failed("input is required for create");
        var timezone = getStringValue(args, "timezone");
        if (timezone == null || timezone.isBlank()) timezone = ZoneId.systemDefault().getId();

        var task = new ScheduledTask();
        task.id = UUID.randomUUID().toString();
        task.sessionId = context.getSessionId();
        task.userId = context.getUserId();
        task.name = getStringValue(args, "name");
        if (task.name == null || task.name.isBlank()) task.name = "task-" + task.id.substring(0, 8);
        task.cronExpression = cronExpression;
        task.timezone = timezone;
        task.input = input;
        task.enabled = Boolean.TRUE;

        var created = store.create(task);
        return ToolCallResult.completed(String.format(
                "Scheduled task created.%nid: %s%nname: %s%ncron_expression: %s%ntimezone: %s%nnext_run_at: %s%ninput: %s",
                created.id, created.name, created.cronExpression, created.timezone,
                created.nextRunAt, created.input));
    }

    private ToolCallResult list(ExecutionContext context) {
        var tasks = store.list(context.getSessionId());
        if (tasks.isEmpty()) {
            return ToolCallResult.completed("No scheduled tasks in this session. Use action=create to schedule one.");
        }
        var lines = new ArrayList<String>();
        lines.add("Scheduled tasks in this session (%d):".formatted(tasks.size()));
        for (var task : tasks) {
            lines.add("- id: %s, name: %s, cron: %s (%s), next_run_at: %s, enabled: %s, input: %s"
                    .formatted(task.id, task.name, task.cronExpression, task.timezone, task.nextRunAt, task.enabled, task.input));
        }
        return ToolCallResult.completed(String.join("\n", lines));
    }

    private ToolCallResult delete(Map<String, Object> args, ExecutionContext context) {
        var id = getStringValue(args, "id");
        if (Strings.isBlank(id)) return ToolCallResult.failed("id is required for delete");
        var task = store.get(id);
        if (task == null) return ToolCallResult.failed("scheduled task not found, id=" + id);
        if (!context.getSessionId().equals(task.sessionId)) {
            return ToolCallResult.failed("cannot delete a scheduled task of another session, id=" + id);
        }
        store.delete(id);
        return ToolCallResult.completed("Scheduled task deleted, id=" + id);
    }

    public static class Builder extends ToolCall.Builder<Builder, ScheduledTaskTool> {
        private ScheduledTaskStore store;

        public Builder store(ScheduledTaskStore store) {
            this.store = store;
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public ScheduledTaskTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "action",
                            "The action to perform: 'create', 'list' or 'delete'").required().enums(List.of("create", "list", "delete")),
                    ToolCallParameters.ParamSpec.of(String.class, "id", "The task id (required for delete)"),
                    ToolCallParameters.ParamSpec.of(String.class, "name", "A short task name used in the injected message prefix (optional for create)"),
                    ToolCallParameters.ParamSpec.of(String.class, "cron_expression", "5-field cron expression, e.g. \"0 9 * * *\" (required for create)"),
                    ToolCallParameters.ParamSpec.of(String.class, "timezone", "IANA zone id, defaults to the local timezone (optional for create)"),
                    ToolCallParameters.ParamSpec.of(String.class, "input", "The message injected into the session when the task fires (required for create)")));
            var tool = new ScheduledTaskTool(store);
            build(tool);
            return tool;
        }
    }
}
