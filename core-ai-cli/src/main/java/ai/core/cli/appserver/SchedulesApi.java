package ai.core.cli.appserver;

import ai.core.schedule.ScheduledTask;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.util.Comparator;

/**
 * The {@code schedules/*} RPC surface: list/get/update/delete over the shared store, plus trigger,
 * which must run on the workspace that owns the task's session (the session lives there).
 *
 * @author stephen
 */
final class SchedulesApi {

    private static final int DEFAULT_RUNS_LIMIT = 20;
    private static final int MAX_RUNS_LIMIT = 200;

    private static RpcException taskNotFound(String id) {
        return RpcException.business("TASK_NOT_FOUND", "scheduled task not found: " + id, "id", id);
    }

    private static String scheduleField(IllegalArgumentException e) {
        var message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("timezone")) return "timezone";
        if (message.contains("not found")) return "id";
        return "cronExpression";
    }

    private static Comparator<ScheduledTask> taskOrder() {
        return Comparator
                .comparing((ScheduledTask task) -> !Boolean.TRUE.equals(task.enabled))
                .thenComparing(task -> task.nextRunAt, Comparator.nullsLast(Comparator.comparing(ZonedDateTime::toInstant)));
    }

    private final EngineBootstrap bootstrap;
    private final EngineSessionRegistry registry;
    private final EngineScheduler scheduler;

    SchedulesApi(EngineBootstrap bootstrap, EngineSessionRegistry registry, EngineScheduler scheduler) {
        this.bootstrap = bootstrap;
        this.registry = registry;
        this.scheduler = scheduler;
    }

    ObjectNode list(ObjectNode params) {
        var scope = Params.optionalText(params, "scope");
        var enabledOnly = params.path("enabledOnly").asBoolean(false);
        var node = Params.object();
        var array = node.putArray("tasks");
        for (var task : bootstrap.scheduledTaskStore.listAll().stream().sorted(taskOrder()).toList()) {
            var owned = scheduler.owns(task);
            if (!owned && "workspace".equals(scope)) continue;
            if (enabledOnly && !Boolean.TRUE.equals(task.enabled)) continue;
            array.add(ScheduledTaskViews.task(task, owned, owned ? registry.sessionTitle(task.sessionId) : null));
        }
        return node;
    }

    ObjectNode get(ObjectNode params) {
        var id = Params.requiredText(params, "id");
        var limit = Math.clamp(Params.intParam(params, "runsLimit", DEFAULT_RUNS_LIMIT), 1, MAX_RUNS_LIMIT);
        var task = bootstrap.scheduledTaskStore.get(id);
        if (task == null) throw taskNotFound(id);
        var owned = scheduler.owns(task);
        var node = Params.object();
        node.set("task", ScheduledTaskViews.task(task, owned, owned ? registry.sessionTitle(task.sessionId) : null));
        var runs = node.putArray("runs");
        for (var run : bootstrap.scheduledTaskStore.runs(id, limit)) {
            runs.add(ScheduledTaskViews.run(run));
        }
        if (owned) {
            node.put("sessionExists", Files.exists(scheduler.sessionFile(task.sessionId)));
        }
        return node;
    }

    ObjectNode update(ObjectNode params) {
        var id = Params.requiredText(params, "id");
        if (bootstrap.scheduledTaskStore.get(id) == null) throw taskNotFound(id);
        var patch = new ScheduledTask();
        patch.id = id;
        patch.name = Params.optionalText(params, "name");
        patch.cronExpression = Params.optionalText(params, "cronExpression");
        patch.timezone = Params.optionalText(params, "timezone");
        patch.input = Params.optionalText(params, "input");
        var enabled = params.get("enabled");
        if (enabled != null && !enabled.isNull()) patch.enabled = enabled.asBoolean();
        ScheduledTask updated;
        try {
            updated = bootstrap.scheduledTaskStore.update(patch);
        } catch (IllegalArgumentException e) {
            throw RpcException.business("INVALID_SCHEDULE", e.getMessage(), "field", scheduleField(e), e);
        }
        scheduler.refreshSnapshot();
        var owned = scheduler.owns(updated);
        var node = Params.object();
        node.set("task", ScheduledTaskViews.task(updated, owned, owned ? registry.sessionTitle(updated.sessionId) : null));
        return node;
    }

    ObjectNode delete(ObjectNode params) {
        var id = Params.requiredText(params, "id");
        bootstrap.scheduledTaskStore.delete(id);
        scheduler.refreshSnapshot();
        return Params.object();
    }

    ObjectNode trigger(ObjectNode params) {
        var id = Params.requiredText(params, "id");
        var run = scheduler.triggerNow(id);
        var node = Params.object();
        node.set("run", ScheduledTaskViews.run(run));
        return node;
    }
}
