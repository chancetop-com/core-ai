package ai.core.server;

import ai.core.api.server.NotificationWebService;
import ai.core.api.server.notification.NotificationSseEvent;
import ai.core.server.notification.NotificationEventPublisher;
import ai.core.server.notification.NotificationService;
import ai.core.server.notification.NotificationTools;
import ai.core.server.notification.SendNotificationHandler;
import ai.core.server.web.NotificationWebServiceImpl;
import ai.core.server.web.sse.NotificationChannelListener;
import ai.core.sse.PatchedServerSentEventConfig;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class NotificationModule extends Module {
    @Override
    protected void initialize() {
        bind(NotificationEventPublisher.class);
        bind(NotificationService.class);
        bind(SendNotificationHandler.class);
        var notificationTools = bind(NotificationTools.class);
        onStartup(notificationTools::initialize);

        api().service(NotificationWebService.class, bind(NotificationWebServiceImpl.class));

        var sseConfig = config(PatchedServerSentEventConfig.class, "core-ai-server-sse");
        sseConfig.listen(HTTPMethod.PUT, "/api/notifications/events", NotificationSseEvent.class, bind(NotificationChannelListener.class));
    }
}
