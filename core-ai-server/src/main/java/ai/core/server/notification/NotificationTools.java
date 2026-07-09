package ai.core.server.notification;

import ai.core.server.tool.ToolRegistryService;
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
    NotificationService notificationService;
    @Inject
    ToolRegistryService toolRegistryService;

    public void initialize() {
        var sendNotification = new SendNotificationTool(notificationService);

        toolRegistryService.registerBuiltinToolGroup(TOOL_ENTRY_ID, "System",
            "System-level tools for notifications and platform interactions",
            List.of(sendNotification));
    }
}
