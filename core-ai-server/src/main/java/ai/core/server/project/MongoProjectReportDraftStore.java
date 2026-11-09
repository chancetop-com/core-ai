package ai.core.server.project;

import ai.core.server.domain.ProjectReportDraft;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public class MongoProjectReportDraftStore implements ProjectReportDraftStore {
    public static MongoProjectReportDraftStore of(core.framework.mongo.MongoCollection<ProjectReportDraft> collection) {
        var store = new MongoProjectReportDraftStore();
        store.collection = collection;
        return store;
    }

    @core.framework.inject.Inject
    core.framework.mongo.MongoCollection<ProjectReportDraft> collection;

    @Override
    public Optional<ProjectReportDraft> get(String id) {
        return collection.get(id);
    }

    @Override
    public void insert(ProjectReportDraft draft) {
        collection.insert(draft);
    }

    @Override
    public void update(String id, Consumer<ProjectReportDraft> mutator) {
        collection.get(id).ifPresent(draft -> {
            mutator.accept(draft);
            collection.replace(draft);
        });
    }

    @Override
    public void delete(String id) {
        collection.delete(id);
    }

    @Override
    public void deleteBySubject(String subjectId) {
        collection.delete(com.mongodb.client.model.Filters.eq("subject_id", subjectId));
    }
}
