package ai.core.api.server.user;

import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.ResponseStatus;

/**
 * Self-service surface for session-completion notifications: a user decides where they are told a
 * chat session finished, without needing the user-management permission.
 *
 * @author stephen
 */
public interface UserNotificationWebService {
    @GET
    @Path("/api/user/notification-settings")
    NotificationSettingsView get();

    @PUT
    @Path("/api/user/notification-settings")
    @ResponseStatus(HTTPStatus.OK)
    void update(UpdateNotificationSettingsRequest request);
}
