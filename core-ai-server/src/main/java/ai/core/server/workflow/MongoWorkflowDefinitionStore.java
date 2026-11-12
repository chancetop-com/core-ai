package ai.core.server.workflow;

import ai.core.server.domain.WorkflowDefinition;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * @author stephen
 */
public class MongoWorkflowDefinitionStore implements WorkflowDefinitionStore {
    public static MongoWorkflowDefinitionStore of(MongoCollection<WorkflowDefinition> collection) {
        var store = new MongoWorkflowDefinitionStore();
        store.definitionCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<WorkflowDefinition> definitionCollection;

    @Override
    public Optional<WorkflowDefinition> get(String id) {
        return definitionCollection.get(id);
    }

    @Override
    public void insert(WorkflowDefinition definition) {
        definitionCollection.insert(definition);
    }

    @Override
    public void replace(WorkflowDefinition definition) {
        definitionCollection.replace(definition);
    }

    @Override
    public void delete(String id) {
        definitionCollection.delete(id);
    }

    @Override
    public List<WorkflowDefinition> listActive(String userId, Boolean myWorkflows, String keyword, int offset, int limit) {
        return find(listFilter(userId, myWorkflows, keyword), offset, limit);
    }

    @Override
    public long countActive(String userId, Boolean myWorkflows, String keyword) {
        return definitionCollection.count(listFilter(userId, myWorkflows, keyword));
    }

    @Override
    public List<WorkflowDefinition> listArchived(String userId, String keyword, int offset, int limit) {
        return find(archivedFilter(userId, keyword), offset, limit);
    }

    @Override
    public long countArchived(String userId, String keyword) {
        return definitionCollection.count(archivedFilter(userId, keyword));
    }

    @Override
    public List<WorkflowDefinition> explore(String userId, String keyword, int offset, int limit) {
        return find(exploreFilter(userId, keyword), offset, limit);
    }

    @Override
    public long exploreCount(String userId, String keyword) {
        return definitionCollection.count(exploreFilter(userId, keyword));
    }

    @Override
    public List<WorkflowDefinition> findByIds(List<String> ids) {
        return ids.isEmpty() ? List.of() : definitionCollection.find(Filters.in("_id", ids));
    }

    @Override
    public List<WorkflowDefinition> listOwned(String userId) {
        return definitionCollection.find(Filters.eq("user_id", userId));
    }

    @Override
    public List<WorkflowDefinition> listPublicActive() {
        return definitionCollection.find(Filters.and(
            Filters.eq("visibility", "PUBLIC"), Filters.eq("status", "ACTIVE"), Filters.ne("published_version_id", null)));
    }

    private List<WorkflowDefinition> find(Bson filter, int offset, int limit) {
        var query = new Query();
        query.filter = filter;
        query.sort = Sorts.descending("updated_at");
        query.skip = Math.max(0, offset);
        query.limit = Math.min(Math.max(limit, 1), 100);
        return definitionCollection.find(query);
    }

    private Bson listFilter(String userId, Boolean myWorkflows, String keyword) {
        var conditions = baseFilters(userId, myWorkflows);
        addKeyword(conditions, keyword);
        return Filters.and(conditions);
    }

    private List<Bson> baseFilters(String userId, Boolean myWorkflows) {
        var conditions = new ArrayList<Bson>();
        if (myWorkflows != null && !myWorkflows) {
            conditions.add(Filters.ne("user_id", userId));
            conditions.add(Filters.ne("published_version_id", null));
            conditions.add(Filters.or(Filters.eq("visibility", "PUBLIC"), Filters.eq("visibility", null)));
        } else {
            conditions.add(Filters.eq("user_id", userId));
        }
        conditions.add(Filters.ne("status", "ARCHIVED"));
        conditions.add(Filters.ne("status", "DISABLED"));
        return conditions;
    }

    private Bson archivedFilter(String userId, String keyword) {
        var conditions = new ArrayList<Bson>();
        conditions.add(Filters.eq("user_id", userId));
        conditions.add(Filters.eq("status", "ARCHIVED"));
        addKeyword(conditions, keyword);
        return Filters.and(conditions);
    }

    private Bson exploreFilter(String userId, String keyword) {
        var conditions = baseFilters(userId, Boolean.FALSE);
        addKeyword(conditions, keyword);
        return Filters.and(conditions);
    }

    private void addKeyword(List<Bson> conditions, String keyword) {
        if (keyword != null && !keyword.isBlank()) conditions.add(Filters.regex("name", Pattern.quote(keyword), "i"));
    }
}
