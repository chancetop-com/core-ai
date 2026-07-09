package ai.core.server.notification;

import ai.core.agent.ExecutionContext;
import ai.core.server.domain.NotificationCategory;
import ai.core.server.domain.NotificationType;
import core.framework.inject.Inject;

/**
 * Handler invoked by the {@code send_notification} tool. The first two
 * parameters ({@code title}, {@code message}) are parsed from the LLM
 * arguments; the third ({@code context}) is auto-injected by
 * {@link ai.core.tool.function.Function}.
 *
 * @author stephen
 */
public class SendNotificationHandler {

    @Inject
    NotificationService notificationService;

    public String send(String title, String message, ExecutionContext context) {
        var userId = context.getUserId();
        var vars = context.getCustomVariables();
        var agentId = vars != null ? (String) vars.get("agentId") : null;
        var sessionId = context.getSessionId();

        if (userId == null) {
            return "notification skipped: no user context available";
        }

        notificationService.create(userId, agentId, sessionId,
            NotificationCategory.AGENT, NotificationType.AGENT_NOTIFICATION,
            title, message);

        return "notification sent: " + title;
    }
}
