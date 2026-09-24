package ai.core.server.session;

import ai.core.api.server.user.NotificationChannelView;
import ai.core.api.server.user.NotificationSettingsView;
import ai.core.api.server.user.UpdateNotificationSettingsRequest;
import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.channel.ChannelRegistry;
import ai.core.server.domain.User;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;

import java.util.ArrayList;
import java.util.List;

/**
 * The user-owned half of session-completion notifications: where to deliver and after how long.
 * A user configures it themselves, so it never requires the user-management permission, and only
 * channels that could actually deliver are offered — enabled, with an outbound adapter, and either
 * platform-wide or personal to this user.
 *
 * @author stephen
 */
public class NotificationSettingsService {
    @Inject
    MongoCollection<User> userCollection;
    @Inject
    ChannelConfigStore channelConfigStore;
    @Inject
    ChannelRegistry channelRegistry;

    public NotificationSettingsView get(String userId) {
        var user = requireUser(userId);
        var view = new NotificationSettingsView();
        view.channelId = user.notifyChannelId;
        view.recipient = user.notifyRecipient;
        view.minMinutes = user.notifyMinMinutes;
        view.channels = deliveryChannels(userId);
        return view;
    }

    public void update(String userId, UpdateNotificationSettingsRequest request) {
        var user = requireUser(userId);
        if (request.minMinutes != null && request.minMinutes < 1) {
            throw new BadRequestException("min_minutes must be >= 1");
        }
        var channelId = trimToNull(request.channelId);
        var recipient = trimToNull(request.recipient);
        if (channelId != null && recipient == null) {
            throw new BadRequestException("recipient is required when a channel is selected");
        }
        if (channelId != null) requireDeliverable(userId, channelId);
        user.notifyChannelId = channelId;
        user.notifyRecipient = recipient;
        if (request.minMinutes != null) user.notifyMinMinutes = request.minMinutes;
        userCollection.replace(user);
    }

    private List<NotificationChannelView> deliveryChannels(String userId) {
        var views = new ArrayList<NotificationChannelView>();
        for (var channel : channelConfigStore.all().values()) {
            if (!Boolean.TRUE.equals(channel.enabled)) continue;
            if (!personalTo(channel.userId, userId)) continue;
            if (!hasOutboundAdapter(channel.channelType)) continue;
            var view = new NotificationChannelView();
            view.channelId = channel.channelId;
            view.channelType = channel.channelType;
            views.add(view);
        }
        return views;
    }

    private void requireDeliverable(String userId, String channelId) {
        var channel = channelConfigStore.load(channelId);
        if (channel == null || !Boolean.TRUE.equals(channel.enabled)) {
            throw new BadRequestException("channel not found or disabled: " + channelId);
        }
        if (!personalTo(channel.userId, userId)) {
            throw new BadRequestException("channel is not available to this user: " + channelId);
        }
        if (!hasOutboundAdapter(channel.channelType)) {
            throw new BadRequestException("channel cannot deliver messages: " + channelId);
        }
    }

    private boolean personalTo(String channelUserId, String userId) {
        return channelUserId == null || channelUserId.isBlank() || channelUserId.equals(userId);
    }

    private boolean hasOutboundAdapter(String channelType) {
        try {
            channelRegistry.outbound(channelType);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private User requireUser(String userId) {
        return userCollection.get(userId)
                .orElseThrow(() -> new NotFoundException("user not found, id=" + userId));
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
