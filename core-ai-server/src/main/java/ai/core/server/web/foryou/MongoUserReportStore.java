package ai.core.server.web.foryou;

import ai.core.server.domain.UserReport;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoUserReportStore implements UserReportStore {
    @Inject
    MongoCollection<UserReport> collection;

    @Override
    public Optional<UserReport> get(String id) {
        return collection.get(id);
    }

    @Override
    public List<UserReport> list(String userId) {
        var query = new Query();
        query.filter = Filters.eq("user_id", userId);
        query.sort = Sorts.descending("updated_at");
        return collection.find(query);
    }

    @Override
    public List<UserReport> recent(String userId, int limit) {
        var query = new Query();
        query.filter = Filters.eq("user_id", userId);
        query.sort = Sorts.descending("updated_at");
        query.limit = limit;
        return collection.find(query);
    }

    @Override
    public long count(String userId) {
        return collection.count(Filters.eq("user_id", userId));
    }

    @Override
    public void insert(UserReport report) {
        collection.insert(report);
    }

    @Override
    public boolean insertIfAbsent(UserReport report) {
        try {
            collection.insert(report);
            return true;
        } catch (com.mongodb.MongoWriteException e) {
            if (e.getCode() == 11000) return false;
            throw e;
        }
    }

    @Override
    public void replace(UserReport report) {
        collection.replace(report);
    }

    @Override
    public void delete(String id) {
        collection.delete(id);
    }
}
