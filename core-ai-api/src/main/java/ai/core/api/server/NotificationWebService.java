package ai.core.api.server;

import ai.core.api.server.notification.ListNotificationsRequest;
import ai.core.api.server.notification.ListNotificationsResponse;
import ai.core.api.server.notification.UnreadCountResponse;
import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;
import core.framework.api.web.service.ResponseStatus;

/**
 * @author stephen
 */
public interface NotificationWebService {

    @GET
    @Path("/api/notifications")
    ListNotificationsResponse list(ListNotificationsRequest request);

    @GET
    @Path("/api/notifications/unread-count")
    UnreadCountResponse unreadCount();

    @POST
    @Path("/api/notifications/:id/read")
    @ResponseStatus(HTTPStatus.NO_CONTENT)
    void markRead(@PathParam("id") String id);

    @POST
    @Path("/api/notifications/read-all")
    @ResponseStatus(HTTPStatus.NO_CONTENT)
    void markAllRead();
}
