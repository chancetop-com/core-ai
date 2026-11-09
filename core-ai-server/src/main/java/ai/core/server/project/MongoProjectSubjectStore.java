package ai.core.server.project;

import ai.core.server.domain.ProjectSubject;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public class MongoProjectSubjectStore implements ProjectSubjectStore {
    public static MongoProjectSubjectStore of(core.framework.mongo.MongoCollection<ProjectSubject> collection) {
        var store = new MongoProjectSubjectStore();
        store.collection = collection;
        return store;
    }

    @core.framework.inject.Inject
    core.framework.mongo.MongoCollection<ProjectSubject> collection;

    @Override
    public Optional<ProjectSubject> get(String id) {
        return collection.get(id);
    }

    @Override
    public List<ProjectSubject> listByProject(String projectId) {
        var query = new core.framework.mongo.Query();
        query.filter = com.mongodb.client.model.Filters.eq("project_id", projectId);
        query.sort = com.mongodb.client.model.Sorts.ascending("created_at");
        return collection.find(query);
    }

    @Override
    public List<ProjectSubject> listStarted(String projectId) {
        return collection.find(com.mongodb.client.model.Filters.and(
            com.mongodb.client.model.Filters.eq("project_id", projectId),
            com.mongodb.client.model.Filters.eq("status", "started")));
    }

    @Override
    public long countStarted(String projectId) {
        return collection.count(com.mongodb.client.model.Filters.and(
            com.mongodb.client.model.Filters.eq("project_id", projectId),
            com.mongodb.client.model.Filters.eq("status", "started")));
    }

    @Override
    public List<ProjectSubject> listWithReportRunId(int limit) {
        var query = new core.framework.mongo.Query();
        query.filter = com.mongodb.client.model.Filters.and(
            com.mongodb.client.model.Filters.exists("report_run_id", true),
            com.mongodb.client.model.Filters.type("report_run_id", "string"));
        query.limit = limit;
        return collection.find(query);
    }

    @Override
    public void insert(ProjectSubject subject) {
        collection.insert(subject);
    }

    @Override
    public boolean insertIfAbsent(ProjectSubject subject) {
        try {
            collection.insert(subject);
            return true;
        } catch (com.mongodb.MongoWriteException e) {
            if (e.getCode() != 11000) throw e;
            return false;
        }
    }

    @Override
    public Optional<ProjectSubject> findAutoByNameKey(String projectId, String nameKey) {
        return collection.find(com.mongodb.client.model.Filters.and(
                com.mongodb.client.model.Filters.eq("project_id", projectId),
                com.mongodb.client.model.Filters.eq("name_key", nameKey),
                com.mongodb.client.model.Filters.eq("source", "auto")))
            .stream().findFirst();
    }

    @Override
    public void update(String id, Consumer<ProjectSubject> mutator) {
        collection.get(id).ifPresent(subject -> {
            mutator.accept(subject);
            collection.replace(subject);
        });
    }

    @Override
    public void delete(String id) {
        collection.delete(id);
    }
}
