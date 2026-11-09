package ai.core.server.project;

import ai.core.server.domain.Project;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * @author stephen
 */
public class MongoProjectStore implements ProjectStore {
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
            return list.stream().map(MongoProjectStore::mongoValue).toList();
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

    @Inject
    MongoCollection<Project> collection;

    @Override
    public Optional<Project> get(String id) {
        return collection.get(id);
    }

    @Override
    public void insert(Project project) {
        collection.insert(project);
    }

    @Override
    public boolean update(String id, Consumer<Project> mutator) {
        return updateIf(id, project -> true, mutator);
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean updateIf(String id, Predicate<Project> guard, Consumer<Project> mutator) {
        var existing = collection.get(id).orElse(null);
        if (existing == null || !guard.test(existing)) return false;
        var before = JsonUtil.toMap(existing);
        var updated = JsonUtil.fromJson(Project.class, JsonUtil.toJson(existing));
        mutator.accept(updated);
        var updates = diff(before, JsonUtil.toMap(updated));
        if (!updates.isEmpty()) collection.update(Filters.eq("_id", id), Updates.combine(updates));
        return true;
    }

    @Override
    public List<Project> list(String userId, Boolean archivedOnly, int offset, int limit) {
        var query = new Query();
        query.filter = listFilter(userId, archivedOnly);
        query.sort = Sorts.descending("created_at");
        query.skip = offset;
        query.limit = limit;
        return collection.find(query);
    }

    @Override
    public long count(String userId, Boolean archivedOnly) {
        return collection.count(listFilter(userId, archivedOnly));
    }

    @Override
    public List<Project> listForAttribution(ZonedDateTime cutoff, int limit) {
        var query = new Query();
        query.filter = Filters.and(
                Filters.eq("status", ProjectService.STATUS_ACTIVE),
                Filters.ne("analysis_status", ProjectService.ANALYSIS_RUNNING),
                Filters.or(Filters.exists("last_analyzed_at", false), Filters.eq("last_analyzed_at", null),
                        Filters.lt("last_analyzed_at", cutoff)));
        query.limit = limit;
        return collection.find(query);
    }

    @Override
    public List<Project> listForAnalysis(ZonedDateTime cutoff, int limit) {
        var query = new Query();
        query.filter = Filters.and(
                Filters.eq("status", ProjectService.STATUS_ACTIVE),
                Filters.ne("analysis_status", ProjectService.ANALYSIS_RUNNING),
                Filters.or(Filters.exists("last_analysis_at", false), Filters.eq("last_analysis_at", null),
                        Filters.lt("last_analysis_at", cutoff)));
        query.limit = limit;
        return collection.find(query);
    }

    @Override
    public List<Project> listDirtyStats(int limit) {
        var query = new Query();
        query.filter = Filters.and(
                Filters.eq("status", ProjectService.STATUS_ACTIVE),
                Filters.eq("stats_dirty", Boolean.TRUE));
        query.limit = limit;
        return collection.find(query);
    }

    private Bson listFilter(String userId, Boolean archivedOnly) {
        var filters = new ArrayList<Bson>();
        if (userId != null) filters.add(Filters.eq("user_id", userId));
        if (Boolean.TRUE.equals(archivedOnly)) {
            filters.add(Filters.eq("status", ProjectService.STATUS_ARCHIVED));
        } else if (Boolean.FALSE.equals(archivedOnly)) {
            filters.add(Filters.eq("status", ProjectService.STATUS_ACTIVE));
        }
        return filters.isEmpty() ? new Document() : Filters.and(filters);
    }
}
