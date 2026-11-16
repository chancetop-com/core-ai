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
    public boolean insertIfAbsent(SkillDefinition definition) {
        if (skillCollection.get(definition.id).isPresent()) return false;
        if (skillCollection.findOne(Filters.eq("qualified_name", definition.qualifiedName)).isPresent()) return false;
        try {
            skillCollection.insert(definition);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void replace(SkillDefinition definition) {
        skillCollection.replace(definition);
    }

    @Override
    public boolean replaceIfRevision(SkillDefinition definition, int expectedRevision) {
        var revisionFilter = expectedRevision == 0
            ? Filters.or(Filters.eq("revision", 0), Filters.exists("revision", false))
            : Filters.eq("revision", expectedRevision);
        var filter = Filters.and(Filters.eq("_id", definition.id), revisionFilter);
        return skillCollection.update(filter, Updates.combine(
            Updates.set("namespace", definition.namespace),
            Updates.set("name", definition.name),
            Updates.set("qualified_name", definition.qualifiedName),
            Updates.set("directory_name", definition.directoryName),
            Updates.set("description", definition.description),
            Updates.set("source_type", definition.sourceType),
            Updates.set("content", definition.content),
            Updates.set("allowed_tools", definition.allowedTools),
            Updates.set("metadata", definition.metadata),
            Updates.set("resources", definition.resources),
            Updates.set("repo_config", definition.repoConfig),
            Updates.set("version", definition.version),
            Updates.set("digest", definition.digest),
            Updates.set("user_id", definition.userId),
            Updates.set("updated_at", definition.updatedAt),
            Updates.set("revision", definition.revision),
            Updates.set("quality_receipt", definition.qualityReceipt))) > 0;
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
