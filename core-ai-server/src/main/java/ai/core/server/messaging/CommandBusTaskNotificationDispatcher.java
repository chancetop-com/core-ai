package ai.core.server.messaging;

import ai.core.server.asynctask.TaskNotificationDispatcher;
import ai.core.server.session.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers a finished long-running tool call over the session command bus, the same route
 * {@code SessionScheduler} uses to fire a scheduled task back into its conversation. Going through the
 * bus is what makes the notification survive the session not being live in this JVM: the owning replica
 * picks the command up, and a session that has been evicted is rebuilt before the injection.
 *
 * @author stephen
 */
public class CommandBusTaskNotificationDispatcher implements TaskNotificationDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandBusTaskNotificationDispatcher.class);

    private final CommandPublisher commandPublisher;
    private final SessionRegistry sessionRegistry;

    public CommandBusTaskNotificationDispatcher(CommandPublisher commandPublisher, SessionRegistry sessionRegistry) {
        this.commandPublisher = commandPublisher;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public Outcome dispatch(String sessionId, String taskId, String toolName, String status, String notificationXml) {
        String userId;
        try {
            var session = sessionRegistry.get(sessionId);
            // A missing or deleted session is not coming back — retrying would only keep the task on the
            // books until the 24h purge, re-announcing it on every backoff window in between.
            if (session == null || session.deletedAt != null) {
                LOGGER.warn("async task finished for a session that no longer exists, taskId={}, sessionId={}", taskId, sessionId);
                return Outcome.ABANDONED;
            }
            userId = session.userId;
        } catch (RuntimeException e) {
            LOGGER.warn("failed to resolve session owner for async task notification, taskId={}, sessionId={}", taskId, sessionId, e);
            return Outcome.RETRY;
        }
        try {
            commandPublisher.publish(SessionCommand.taskNotification(sessionId, userId, taskId, toolName, status, notificationXml));
            return Outcome.DISPATCHED;
        } catch (RuntimeException e) {
            LOGGER.warn("failed to publish async task notification, taskId={}, sessionId={}", taskId, sessionId, e);
            return Outcome.RETRY;
        }
    }
}
