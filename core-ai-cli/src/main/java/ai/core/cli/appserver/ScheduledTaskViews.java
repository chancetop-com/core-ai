package ai.core.cli.appserver;

import ai.core.schedule.ScheduledTask;
import ai.core.schedule.ScheduledTaskRun;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.ZonedDateTime;

/**
 * JSON shapes for the {@code schedules/*} RPCs; kept out of the engine classes so they stay under
 * the file length limit.
 *
 * @author stephen
 */
final class ScheduledTaskViews {

    static ObjectNode task(ScheduledTask task, boolean owned, String sessionTitle) {
        var node = Params.object();
        node.put("id", task.id);
        node.put("sessionId", task.sessionId);
        node.put("name", task.name);
        node.put("cronExpression", task.cronExpression);
        node.put("timezone", task.timezone);
        node.put("input", task.input);
        node.put("enabled", Boolean.TRUE.equals(task.enabled));
        putDate(node, "nextRunAt", task.nextRunAt);
        putDate(node, "createdAt", task.createdAt);
        putDate(node, "updatedAt", task.updatedAt);
        node.put("workspace", task.workspace);
        node.put("workspaceName", workspaceName(task.workspace));
        node.put("owned", owned);
        putDate(node, "lastRunAt", task.lastRunAt);
        node.put("lastRunStatus", task.lastRunStatus);
        node.put("runCount", task.runCount == null ? 0 : task.runCount);
        node.put("disabledReason", task.disabledReason);
        node.put("sessionTitle", sessionTitle);
        return node;
    }

    static ObjectNode run(ScheduledTaskRun run) {
        var node = Params.object();
        node.put("id", run.id);
        node.put("taskId", run.taskId);
        node.put("sessionId", run.sessionId);
        putDate(node, "scheduledFor", run.scheduledFor);
        putDate(node, "firedAt", run.firedAt);
        node.put("lateMillis", run.lateMillis);
        node.put("manual", Boolean.TRUE.equals(run.manual));
        node.put("status", run.status);
        node.put("error", run.error);
        return node;
    }

    private static void putDate(ObjectNode node, String name, ZonedDateTime value) {
        // Offset form (no [Zone] suffix) mirrors the on-disk format and parses with JS Date.parse.
        node.put(name, value == null ? null : value.toOffsetDateTime().toString());
    }

    private static String workspaceName(String workspace) {
        if (workspace == null) return null;
        try {
            var name = Path.of(workspace).getFileName();
            return name == null ? workspace : name.toString();
        } catch (RuntimeException e) {
            return workspace;
        }
    }

    private ScheduledTaskViews() {
    }
}
