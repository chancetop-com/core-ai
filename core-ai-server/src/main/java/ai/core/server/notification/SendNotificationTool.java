package ai.core.server.notification;

import ai.core.agent.ExecutionContext;
import ai.core.server.domain.NotificationCategory;
import ai.core.server.domain.NotificationType;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.tool.ToolCallResult;

import java.util.List;
import java.util.Map;

/**
 * Tool that agents call to deliver user-facing notifications. The agent is
 * expected to provide {@code title} and {@code message}; the {@code user_id}
 * and {@code agent_id} are resolved from the execution context automatically.
 *
 * @author stephen
 */
public class SendNotificationTool extends ToolCall {

    private final NotificationService notificationService;

    public SendNotificationTool(NotificationService notificationService) {
        this.notificationService = notificationService;
        this.name = "send_notification";
        this.description = "Send a user-facing notification. Use this to inform the user about important "
            + "events, milestones, or results during a long-running task. Notifications are delivered "
            + "via server-sent events (SSE) and persisted for later review.";
        this.parameters = List.of(
            ToolCallParameter.builder()
                .name("title").description("Notification title").type(ToolCallParameterType.STRING).required(true).build(),
            ToolCallParameter.builder()
                .name("message").description("Notification body text").type(ToolCallParameterType.STRING).required(true).build()
        );
        this.llmVisible = true;
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        var userId = context.getUserId();
        var vars = context.getCustomVariables();
        var agentId = vars != null ? (String) vars.get("agentId") : null;
        var sessionId = context.getSessionId();

        if (userId == null) {
            return ToolCallResult.completed("notification skipped: no user context available");
        }

        var args = parseArguments(arguments);
        var title = (String) args.get("title");
        var message = (String) args.get("message");

        if (title == null || title.isBlank() || message == null || message.isBlank()) {
            throw new IllegalArgumentException("title and message are required");
        }

        notificationService.create(userId, agentId, sessionId,
            NotificationCategory.AGENT, NotificationType.AGENT_NOTIFICATION,
            title, message);

        return ToolCallResult.completed("notification sent: " + title);
    }

    @Override
    public ToolCallResult execute(String arguments) {
        throw new UnsupportedOperationException(
            "send_notification requires execution context; invoke via execute(arguments, context)");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) return Map.of();
        return (Map<String, Object>) core.framework.json.JSON.fromJSON(Map.class, arguments);
    }
}
