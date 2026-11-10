package ai.core.server.channel;

import ai.core.server.domain.UserChannelTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Remembers where a user last talked from on each channel. Rows are keyed by (user, channel) and
 * looked up by that key, so no query ever scans the collection.
 *
 * @author stephen
 */
public abstract class UserChannelTargetStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(UserChannelTargetStore.class);

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    protected abstract Optional<UserChannelTarget> get(String id);

    protected abstract void insert(UserChannelTarget target);

    protected abstract void replace(UserChannelTarget target);

    public abstract boolean insertIfAbsent(UserChannelTarget target);

    public void record(String userId, String channelId, String recipient) {
        if (!isSet(userId) || !isSet(channelId) || !isSet(recipient)) return; // nothing addressable to remember
        var target = new UserChannelTarget();
        target.id = UserChannelTarget.idOf(userId, channelId);
        target.userId = userId;
        target.channelId = channelId;
        target.recipient = recipient;
        target.updatedAt = ZonedDateTime.now();
        try {
            if (get(target.id).isPresent()) {
                replace(target);
            } else {
                insert(target);
            }
        } catch (RuntimeException e) {
            // remembering an address must never break an inbound message
            LOGGER.warn("failed to record channel target, userId={}, channelId={}", userId, channelId, e);
        }
    }

    public UserChannelTarget load(String userId, String channelId) {
        return get(UserChannelTarget.idOf(userId, channelId)).orElse(null);
    }
}
