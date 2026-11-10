package ai.core.server.web.foryou;

import ai.core.server.domain.UserTodo;
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
public class MongoUserTodoStore implements UserTodoStore {
    @Inject
    MongoCollection<UserTodo> collection;

    @Override
    public Optional<UserTodo> get(String id) {
        return collection.get(id);
    }

    @Override
    public List<UserTodo> list(String userId) {
        var query = new Query();
        query.filter = Filters.eq("user_id", userId);
        query.sort = Sorts.ascending("created_at");
        return collection.find(query);
    }

    @Override
    public List<UserTodo> active(String userId) {
        var query = new Query();
        query.filter = Filters.and(Filters.eq("user_id", userId), Filters.eq("completed", Boolean.FALSE));
        query.sort = Sorts.ascending("created_at");
        return collection.find(query);
    }

    @Override
    public long count(String userId) {
        return collection.count(Filters.eq("user_id", userId));
    }

    @Override
    public long activeCount(String userId) {
        return collection.count(Filters.and(Filters.eq("user_id", userId), Filters.eq("completed", Boolean.FALSE)));
    }

    @Override
    public void insert(UserTodo todo) {
        collection.insert(todo);
    }

    @Override
    public boolean insertIfAbsent(UserTodo todo) {
        try {
            collection.insert(todo);
            return true;
        } catch (com.mongodb.MongoWriteException e) {
            if (e.getCode() == 11000) return false;
            throw e;
        }
    }

    @Override
    public void replace(UserTodo todo) {
        collection.replace(todo);
    }

    @Override
    public void delete(String id) {
        collection.delete(id);
    }
}
