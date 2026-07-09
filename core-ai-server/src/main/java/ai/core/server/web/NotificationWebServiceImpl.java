package ai.core.server.web;

import ai.core.api.server.NotificationWebService;
import ai.core.api.server.notification.ListNotificationsRequest;
import ai.core.api.server.notification.ListNotificationsResponse;
import ai.core.api.server.notification.NotificationView;
import ai.core.api.server.notification.UnreadCountResponse;
import ai.core.server.domain.NotificationCategory;
import ai.core.server.domain.NotificationStatus;
import ai.core.server.notification.NotificationService;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.WebContext;

/**
 * @author stephen
 */
public class NotificationWebServiceImpl implements NotificationWebService {

    @Inject
    NotificationService notificationService;
    @Inject
    WebContext webContext;

    @Override
    public ListNotificationsResponse list(ListNotificationsRequest request) {
        var userId = AuthContext.userId(webContext);
        var categoryEnum = request.category != null ? NotificationCategory.valueOf(request.category.toUpperCase()) : null;
        var statusEnum = request.status != null ? NotificationStatus.valueOf(request.status.toUpperCase()) : null;
        int off = request.offset != null ? request.offset : 0;
        int lim = request.limit != null ? request.limit : 50;

        var notifications = notificationService.list(userId, categoryEnum, statusEnum, off, lim);
        long total = notificationService.count(userId, categoryEnum, statusEnum);

        var response = new ListNotificationsResponse();
        response.notifications = notifications.stream().map(n -> toView(n)).toList();
        response.total = total;
        return response;
    }

    @Override
    public UnreadCountResponse unreadCount() {
        var userId = AuthContext.userId(webContext);
        var response = new UnreadCountResponse();
        response.unreadCount = notificationService.unreadCount(userId);
        return response;
    }

    @Override
    public void markRead(String id) {
        var userId = AuthContext.userId(webContext);
        notificationService.markRead(id, userId);
    }

    @Override
    public void markAllRead() {
        var userId = AuthContext.userId(webContext);
        notificationService.markAllRead(userId);
    }

    private NotificationView toView(ai.core.server.domain.Notification entity) {
        var view = new NotificationView();
        view.id = entity.id;
        view.category = entity.category.name();
        view.type = entity.type.name();
        view.title = entity.title;
        view.message = entity.message;
        view.agentId = entity.agentId;
        view.sessionId = entity.sessionId;
        view.status = entity.status.name();
        view.createdAt = entity.createdAt;
        view.readAt = entity.readAt;
        return view;
    }
}
