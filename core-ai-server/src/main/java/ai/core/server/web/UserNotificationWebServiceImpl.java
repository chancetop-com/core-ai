package ai.core.server.web;

import ai.core.api.server.user.NotificationSettingsView;
import ai.core.api.server.user.UpdateNotificationSettingsRequest;
import ai.core.api.server.user.UserNotificationWebService;
import ai.core.server.rbac.PermissionsBypass;
import ai.core.server.session.NotificationSettingsService;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.WebContext;

/**
 * @author stephen
 */
@PermissionsBypass
public class UserNotificationWebServiceImpl implements UserNotificationWebService {
    @Inject
    WebContext webContext;
    @Inject
    NotificationSettingsService notificationSettingsService;

    @Override
    public NotificationSettingsView get() {
        return notificationSettingsService.get(userId());
    }

    @Override
    public void update(UpdateNotificationSettingsRequest request) {
        var userId = userId();
        ActionLogContext.put("user_id", userId);
        notificationSettingsService.update(userId, request);
    }

    private String userId() {
        return AuthContext.userId(webContext);
    }
}
