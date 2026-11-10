package ai.core.server.session;

import ai.core.server.domain.SessionNotice;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
public class MongoSessionNoticeStore extends SessionNoticeStore {
    @Inject
    MongoCollection<SessionNotice> collection;

    @Override
    protected void insert(SessionNotice notice) {
        collection.insert(notice);
    }

    @Override
    public boolean insertIfAbsent(SessionNotice notice) {
        try {
            collection.insert(notice);
            return true;
        } catch (com.mongodb.MongoWriteException e) {
            if (e.getCode() == 11000) return false;
            throw e;
        }
    }

    @Override
    protected void updateAnswered(String userId, String channelId, String recipient, ZonedDateTime at) {
        collection.update(filter(userId, channelId, recipient),
                Updates.combine(Updates.set("answered", Boolean.TRUE), Updates.set("answered_at", at)));
    }

    @Override
    protected List<SessionNotice> findUnanswered(String userId, String channelId, String recipient) {
        var query = new Query();
        query.filter = Filters.and(filter(userId, channelId, recipient), Filters.eq("answered", Boolean.FALSE));
        query.sort = Sorts.descending("created_at");
        query.limit = MAX_UNANSWERED;
        return collection.find(query);
    }

    private Bson filter(String userId, String channelId, String recipient) {
        var filters = new ArrayList<Bson>(3);
        filters.add(Filters.eq("user_id", userId));
        filters.add(Filters.eq("channel_id", channelId));
        filters.add(Filters.eq("recipient", recipient));
        return Filters.and(filters);
    }
}

