package ai.core.server.notification;

import ai.core.server.tool.ToolRegistryService;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.tool.function.Function;
import core.framework.inject.Inject;

import java.util.List;

/**
 * Registers the {@code system} builtin tool group containing
 * {@code send_notification} and future system-level tools.
 *
 * @author stephen
 */
public class NotificationTools {
    static final String TOOL_SET_NAME = "system";
    static final String TOOL_ENTRY_ID = "builtin:" + TOOL_SET_NAME;

    @Inject
    SendNotificationHandler sendNotificationHandler;
    @Inject
    ToolRegistryService toolRegistryService;

    public void initialize() {
        var params = List.of(
            ToolCallParameter.builder()
                .name("title").description("Notification title").type(ToolCallParameterType.STRING).required(Boolean.TRUE).build(),
            ToolCallParameter.builder()
                .name("message").description("Notification body text").type(ToolCallParameterType.STRING).required(Boolean.TRUE).build()
        );

        var method = findSendMethod();
        var sendNotificationTool = Function.builder()
            .namespace(TOOL_SET_NAME)
            .sourceType(TOOL_SET_NAME)
            .name("send_notification")
            .description("Send a user-facing notification. Use this to inform the user about important "
                + "events, milestones, or results during a long-running task. Notifications are delivered "
                + "via server-sent events (SSE) and persisted for later review.")
            .object(sendNotificationHandler)
            .method(method)
            .parameters(params)
            .llmVisible(Boolean.TRUE)
            .build();

        toolRegistryService.registerBuiltinToolGroup(TOOL_ENTRY_ID, "System",
            "System-level tools for notifications and platform interactions",
            List.of(sendNotificationTool));
    }

    private java.lang.reflect.Method findSendMethod() {
        try {
            return SendNotificationHandler.class.getMethod("send",
                String.class, String.class, ai.core.agent.ExecutionContext.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("send method not found on SendNotificationHandler", e);
        }
    }
}
