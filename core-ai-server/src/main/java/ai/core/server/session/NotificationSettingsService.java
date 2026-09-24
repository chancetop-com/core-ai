package ai.core.server.session;

import ai.core.api.server.user.NotificationChannelView;
import ai.core.api.server.user.NotificationSettingsView;
import ai.core.api.server.user.NotificationTargetView;
import ai.core.api.server.user.UpdateNotificationSettingsRequest;
import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.channel.ChannelConfigView;
import ai.core.server.channel.ChannelRegistry;
import ai.core.server.channel.UserChannelTargetStore;
import ai.core.server.domain.User;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The user-owned half of session-completion notifications: where to deliver and after how long.
 * A user configures it themselves, so it never requires the user-management permission. Only
 * channels that could actually deliver are offered, and each one carries the address the user was
 * last seen writing from — a QQ openid is only knowable from an inbound message, so typing it
 * cannot be the expected path.
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
    @Inject
    UserChannelTargetStore userChannelTargetStore;

    public NotificationSettingsView get(String userId) {
        var user = requireUser(userId);
        var view = new NotificationSettingsView();
        view.channelId = user.notifyChannelId;
        view.recipient = user.notifyRecipient;
        view.minMinutes = user.notifyMinMinutes;
        var deliverable = deliverableChannels(userId);
        view.channels = new ArrayList<>(deliverable.size());
        view.targets = new ArrayList<>(deliverable.size());
        for (var channel : deliverable) {
            var channelView = new NotificationChannelView();
            channelView.channelId = channel.channelId;
            channelView.channelType = channel.channelType;
            view.channels.add(channelView);

            var target = userChannelTargetStore.load(userId, channel.channelId);
            if (target == null) continue;
            var targetView = new NotificationTargetView();
            targetView.channelId = channel.channelId;
            targetView.channelType = channel.channelType;
            targetView.recipient = target.recipient;
            view.targets.add(targetView);
        }
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

    private List<ChannelConfigView> deliverableChannels(String userId) {
        var channels = new ArrayList<ChannelConfigView>();
        for (var channel : channelConfigStore.all().values()) {
            if (!Boolean.TRUE.equals(channel.enabled)) continue;
            if (!personalTo(channel.userId, userId)) continue;
            if (!hasOutboundAdapter(channel.channelType)) continue;
            channels.add(channel);
        }
        channels.sort(Comparator.comparing(channel -> channel.channelId));
        return channels;
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
