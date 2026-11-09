package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillSourceType;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoSkillStore implements SkillStore {
    public static MongoSkillStore of(MongoCollection<SkillDefinition> skillCollection) {
        var store = new MongoSkillStore();
        store.skillCollection = skillCollection;
        return store;
    }

    @Inject
    MongoCollection<SkillDefinition> skillCollection;

    @Override
    public Optional<SkillDefinition> get(String id) {
        return skillCollection.get(id);
    }

    @Override
    public Optional<SkillDefinition> findByQualifiedName(String qualifiedName) {
        return skillCollection.findOne(Filters.eq("qualified_name", qualifiedName));
    }

    @Override
    public List<SkillDefinition> findByName(String name) {
        return skillCollection.find(Filters.eq("name", name));
    }

    @Override
    public List<SkillDefinition> listByIds(Collection<String> ids) {
        if (ids.isEmpty()) return List.of();
        return skillCollection.find(Filters.in("_id", ids.toArray(new String[0])));
    }

    @Override
    public List<SkillDefinition> list(String namespace, SkillSourceType sourceType, Integer offset, Integer limit) {
        var query = new Query();
        query.filter = filter(namespace, sourceType);
        query.sort = com.mongodb.client.model.Sorts.descending("updated_at");
        if (offset != null || limit != null) {
            query.skip = Math.max(0, offset != null ? offset : 0);
            query.limit = Math.clamp(limit != null ? limit : 20, 1, 100);
        }
        return skillCollection.find(query);
    }

    @Override
    public long count(String namespace, SkillSourceType sourceType) {
        return skillCollection.count(filter(namespace, sourceType));
    }

    @Override
    public List<SkillDefinition> listBySourceType(SkillSourceType sourceType) {
        return skillCollection.find(Filters.eq("source_type", sourceType));
    }

    @Override
    public List<SkillDefinition> listAll() {
        return skillCollection.find(new Query());
    }

    @Override
    public List<SkillDefinition> listCatalog() {
        var query = new Query();
        query.projection = com.mongodb.client.model.Projections.exclude("content", "resources.content");
        return skillCollection.find(query);
    }

    @Override
    public long countByNamespace(String namespace) {
        return skillCollection.count(Filters.eq("namespace", namespace));
    }

    @Override
    public long countBySourceType(SkillSourceType sourceType) {
        return skillCollection.count(Filters.eq("source_type", sourceType));
    }

    @Override
    public void insert(SkillDefinition definition) {
        skillCollection.insert(definition);
    }

    @Override
    public void replace(SkillDefinition definition) {
        skillCollection.replace(definition);
    }

    @Override
    public void delete(String id) {
        skillCollection.delete(id);
    }

    @Override
    public void recordShow(String qualifiedName) {
        skillCollection.update(Filters.eq("qualified_name", qualifiedName), Updates.inc("stats.show_count", 1));
    }

    @Override
    public void recordPull(String id) {
        skillCollection.update(Filters.eq("_id", id), Updates.combine(
            Updates.inc("stats.pull_count", 1),
            Updates.set("stats.last_pulled_at", ZonedDateTime.now())));
    }

    private org.bson.conversions.Bson filter(String namespace, SkillSourceType sourceType) {
        var filters = new java.util.ArrayList<org.bson.conversions.Bson>();
        if (namespace != null && !namespace.isBlank()) {
            filters.add(Filters.eq("namespace", namespace));
        }
        if (sourceType != null) {
            filters.add(Filters.eq("source_type", sourceType));
        }
        return filters.isEmpty() ? Filters.empty() : Filters.and(filters);
    }
}
