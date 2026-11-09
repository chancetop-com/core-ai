package ai.core.server.project;

import ai.core.server.domain.ProjectStatsEntity;

import java.util.Optional;

/**
 * @author stephen
 */
public class MongoProjectStatsStore implements ProjectStatsStore {
    public static MongoProjectStatsStore of(core.framework.mongo.MongoCollection<ProjectStatsEntity> collection) {
        var store = new MongoProjectStatsStore();
        store.collection = collection;
        return store;
    }

    @core.framework.inject.Inject
    core.framework.mongo.MongoCollection<ProjectStatsEntity> collection;

    @Override
    public Optional<ProjectStatsEntity> get(String id) {
        return collection.get(id);
    }

    @Override
    public void replace(ProjectStatsEntity entity) {
        collection.replace(entity);
    }
}
