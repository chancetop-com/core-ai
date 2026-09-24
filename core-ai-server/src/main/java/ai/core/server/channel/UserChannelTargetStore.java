package ai.core.server.channel;

import ai.core.server.domain.UserChannelTarget;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;

/**
 * Remembers where a user last talked from on each channel. Rows are keyed by (user, channel) and
 * looked up by that key, so no query ever scans the collection.
 *
 * @author stephen
 */
public class UserChannelTargetStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(UserChannelTargetStore.class);

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    @Inject
    MongoCollection<UserChannelTarget> collection;

    public void record(String userId, String channelId, String recipient) {
        if (!isSet(userId) || !isSet(channelId) || !isSet(recipient)) return; // nothing addressable to remember
        var target = new UserChannelTarget();
        target.id = UserChannelTarget.idOf(userId, channelId);
        target.userId = userId;
        target.channelId = channelId;
        target.recipient = recipient;
        target.updatedAt = ZonedDateTime.now();
        try {
            if (collection.get(target.id).isPresent()) {
                collection.replace(target);
            } else {
                collection.insert(target);
            }
        } catch (RuntimeException e) {
            // remembering an address must never break an inbound message
            LOGGER.warn("failed to record channel target, userId={}, channelId={}", userId, channelId, e);
        }
    }

    public UserChannelTarget load(String userId, String channelId) {
        return collection.get(UserChannelTarget.idOf(userId, channelId)).orElse(null);
    }
}
