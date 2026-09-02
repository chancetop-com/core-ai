package ai.core.server.asynctask;

import ai.core.server.tool.ToolRegistryService;
import ai.core.session.InProcessAgentSession;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallAsyncTask;
import ai.core.tool.ToolCallAsyncTaskManager;
import ai.core.tool.ToolCallResult;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Function;

/**
 * The one place long-running tool calls live on the server. Every {@code pending} result any tool
 * returns (clip renders, assembly, enhancement, post-process, video generation) is stored by the
 * executor through {@link #manager()}, refreshed here on a fixed rate, and — when it turns terminal —
 * injected into the issuing session as a task notification, exactly like a background agent finishing.
 * Domain queues (render, assembly) keep their own dispatchers; this layer is about the tool call's
 * lifecycle, not about doing the work.
 *
 * @author stephen
 */
public class AsyncToolTaskService {
    static final Duration TERMINAL_RETENTION = Duration.ofHours(24);
    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncToolTaskService.class);
    private static final int MAX_NOTIFICATION_RESULT_LENGTH = 30 * 1024;

    @Inject
    MongoAsyncTaskPersistence persistence;
    @Inject
    ToolRegistryService toolRegistryService;

    private volatile ToolCallAsyncTaskManager manager;
    private Function<String, InProcessAgentSession> sessionLocator = sessionId -> null;

    public ToolCallAsyncTaskManager manager() {
        var current = manager;
        if (current == null) {
            synchronized (this) {
                if (manager == null) {
                    var created = new ServerAsyncTaskManager(persistence, toolRegistryService);
                    created.addTerminalListener(this::notifySession);
                    manager = created;
                }
                current = manager;
            }
        }
        return current;
    }

    /** Live-session lookup; sessions that are not in memory (ended, other replica) simply get no push. */
    public void setSessionLocator(Function<String, InProcessAgentSession> sessionLocator) {
        this.sessionLocator = sessionLocator;
    }

    /** One tick: refresh every open task through its tool, then drop long-finished ones. */
    public void pollOpenTasks() {
        var current = manager();
        for (var taskId : current.listOpenTaskIds()) {
            try {
                current.pollTask(taskId);
            } catch (Exception e) {
                LOGGER.warn("async task poll failed, taskId={}", taskId, e);
            }
        }
        current.purgeTerminalOlderThan(TERMINAL_RETENTION);
    }

    void notifySession(String sessionId, ToolCallAsyncTask task, ToolCallResult result) {
        if (sessionId == null) return;
        var session = sessionLocator.apply(sessionId);
        if (session == null) {
            LOGGER.info("async task finished for a session that is not live, taskId={}, session={}", task.taskId(), sessionId);
            return;
        }
        var status = result.isCompleted() ? "completed" : "failed";
        session.notifyTask(task.taskId(), status, notificationXml(task, status, result));
        LOGGER.info("async task notification delivered, taskId={}, tool={}, session={}, status={}", task.taskId(), task.tool().getName(), sessionId, status);
    }

    private String notificationXml(ToolCallAsyncTask task, String status, ToolCallResult result) {
        var body = truncate(result.getResult());
        var payload = "completed".equals(status) ? "<result>" + body + "</result>" : "<error>" + body + "</error>";
        return "<task-notification>%n<task-id>%s</task-id>%n<tool>%s</tool>%n<status>%s</status>%n%s%n</task-notification>%n"
            .formatted(task.taskId(), task.tool().getName(), status, payload);
    }

    private String truncate(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_NOTIFICATION_RESULT_LENGTH) return text;
        return text.substring(0, MAX_NOTIFICATION_RESULT_LENGTH) + "\n\n[Output truncated: showing first " + MAX_NOTIFICATION_RESULT_LENGTH + " characters]";
    }

    /** Falls back to the server tool catalogue, so a task survives even when no tool was registered by hand. */
    static final class ServerAsyncTaskManager extends ToolCallAsyncTaskManager {
        private final ToolRegistryService toolRegistryService;

        ServerAsyncTaskManager(MongoAsyncTaskPersistence persistence, ToolRegistryService toolRegistryService) {
            super(persistence);
            this.toolRegistryService = toolRegistryService;
        }

        @Override
        protected ToolCall resolveTool(String toolName) {
            var registered = super.resolveTool(toolName);
            return registered != null ? registered : toolRegistryService.findBuiltinTool(toolName);
        }
    }
}
