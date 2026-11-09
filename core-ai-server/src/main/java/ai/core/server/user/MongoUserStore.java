package ai.core.server.user;

import ai.core.server.domain.User;
import ai.core.utils.JsonUtil;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public class MongoUserStore implements UserStore {
    private static final Set<String> SCALAR_TYPES = Set.of(
            "java.lang.String", "java.lang.Boolean", "java.lang.Integer", "java.lang.Long", "java.lang.Double",
            "java.time.ZonedDateTime");

    private static String field(String javaField) {
        if ("id".equals(javaField)) return "_id";
        var builder = new StringBuilder();
        for (char c : javaField.toCharArray()) {
            if (Character.isUpperCase(c)) {
                builder.append('_').append(Character.toLowerCase(c));
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private static Object mongoValue(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(MongoUserStore::mongoValue).toList();
        }
        if (value == null || SCALAR_TYPES.contains(value.getClass().getName())) return value;
        return Document.parse(JsonUtil.toJson(value));
    }

    private static List<Bson> diff(Map<String, Object> before, Map<String, Object> after) {
        var updates = new ArrayList<Bson>();
        var fields = new LinkedHashSet<String>();
        fields.addAll(before.keySet());
        fields.addAll(after.keySet());
        for (var key : fields) {
            var oldValue = before.get(key);
            var newValue = after.get(key);
            if (Objects.equals(oldValue, newValue)) continue;
            if (newValue == null) {
                updates.add(Updates.unset(field(key)));
            } else {
                updates.add(Updates.set(field(key), mongoValue(newValue)));
            }
        }
        return updates;
    }

    public static MongoUserStore of(MongoCollection<User> collection) {
        var store = new MongoUserStore();
        store.collection = collection;
        return store;
    }

    @Inject
    MongoCollection<User> collection;

    @Override
    public Optional<User> get(String id) {
        return collection.get(id);
    }

    @Override
    public List<User> listByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        var query = new Query();
        query.filter = Filters.in("_id", ids);
        return collection.find(query);
    }

    @Override
    public List<User> listAll() {
        return collection.find(new Query());
    }

    @Override
    public List<User> listByUserType(String userType) {
        return collection.find(Filters.eq("user_type", userType));
    }

    @Override
    public List<User> listInternalAndApiUsers() {
        var query = new Query();
        query.filter = Filters.in("user_type", "internal", "api");
        query.sort = Sorts.ascending("created_at");
        return collection.find(query);
    }

    @Override
    public List<User> listUsersWithEmail(int limit) {
        var query = new Query();
        query.filter = Filters.exists("email");
        query.limit = limit;
        return collection.find(query);
    }

    @Override
    public List<String> listUserIdsByType(String userType, int limit) {
        var query = new Query();
        query.filter = Filters.eq("user_type", userType);
        query.limit = limit;
        return collection.find(query).stream().map(user -> user.id).toList();
    }

    @Override
    public Optional<User> findByApiKey(String apiKey) {
        return collection.findOne(Filters.and(Filters.eq("api_key", apiKey), Filters.type("api_key", "string")));
    }

    @Override
    public Optional<User> findByOwnerAndExternalId(String ownerId, String externalId) {
        return collection.findOne(Filters.and(
                Filters.eq("owner_id", ownerId),
                Filters.eq("external_id", externalId),
                Filters.type("owner_id", "string"),
                Filters.type("external_id", "string")));
    }

    @Override
    public void insert(User user) {
        collection.insert(user);
    }

    @Override
    public void replace(User user) {
        collection.replace(user);
    }

    @Override
    public void delete(String id) {
        collection.delete(id);
    }

    @Override
    public void incrementQuotaUsage(String userId, long inputTokens, long outputTokens) {
        if (inputTokens > 0) {
            collection.update(Filters.and(
                    Filters.eq("_id", userId),
                    Filters.ne("quota_input_tokens", null),
                    Filters.gt("quota_input_tokens", 0)),
                Updates.inc("quota_consumed_input_tokens", inputTokens));
        }
        if (outputTokens > 0) {
            collection.update(Filters.and(
                    Filters.eq("_id", userId),
                    Filters.ne("quota_output_tokens", null),
                    Filters.gt("quota_output_tokens", 0)),
                Updates.inc("quota_consumed_output_tokens", outputTokens));
        }
    }

    @Override
    public void resetQuota(String userId, ZonedDateTime now) {
        collection.update(Filters.eq("_id", userId), Updates.combine(
            Updates.set("quota_consumed_input_tokens", 0L),
            Updates.set("quota_consumed_output_tokens", 0L),
            Updates.set("quota_window_start", now)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean update(String id, Consumer<User> mutator) {
        var existing = collection.get(id).orElse(null);
        if (existing == null) return false;
        var before = JsonUtil.toMap(existing);
        var updated = JsonUtil.fromJson(User.class, JsonUtil.toJson(existing));
        mutator.accept(updated);
        var updates = diff(before, JsonUtil.toMap(updated));
        if (!updates.isEmpty()) collection.update(Filters.eq("_id", id), Updates.combine(updates));
        return true;
    }
}
