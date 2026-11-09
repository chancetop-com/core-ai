package ai.core.server.project;

import ai.core.server.domain.ProjectSubjectEvent;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoProjectEventStore implements ProjectEventStore {
    public static MongoProjectEventStore of(core.framework.mongo.MongoCollection<ProjectSubjectEvent> collection) {
        var store = new MongoProjectEventStore();
        store.collection = collection;
        return store;
    }

    @core.framework.inject.Inject
    core.framework.mongo.MongoCollection<ProjectSubjectEvent> collection;

    @Override
    public Optional<ZonedDateTime> latestAt(String subjectId) {
        var query = new core.framework.mongo.Query();
        query.filter = com.mongodb.client.model.Filters.eq("subject_id", subjectId);
        query.sort = com.mongodb.client.model.Sorts.descending("at");
        query.limit = 1;
        return collection.find(query).stream().map(event -> event.at).findFirst();
    }

    @Override
    public long countByProjectAndSubject(String projectId, String subjectId) {
        return collection.count(com.mongodb.client.model.Filters.and(
            com.mongodb.client.model.Filters.eq("project_id", projectId),
            com.mongodb.client.model.Filters.eq("subject_id", subjectId)));
    }

    @Override
    public List<ProjectSubjectEvent> listByProjectAndSubject(String projectId, String subjectId, int offset, int limit) {
        var query = new core.framework.mongo.Query();
        query.filter = com.mongodb.client.model.Filters.and(
            com.mongodb.client.model.Filters.eq("project_id", projectId),
            com.mongodb.client.model.Filters.eq("subject_id", subjectId));
        query.sort = com.mongodb.client.model.Sorts.ascending("at");
        query.skip = offset;
        query.limit = limit;
        return collection.find(query);
    }

    @Override
    public List<ProjectSubjectEvent> listBySubjectAndType(String subjectId, String type, int limit) {
        var query = new core.framework.mongo.Query();
        query.filter = com.mongodb.client.model.Filters.and(
            com.mongodb.client.model.Filters.eq("subject_id", subjectId),
            com.mongodb.client.model.Filters.eq("type", type));
        query.sort = com.mongodb.client.model.Sorts.descending("at");
        query.limit = limit;
        return collection.find(query);
    }

    @Override
    public List<ProjectSubjectEvent> listByProject(String projectId, String subjectId, String type,
                                                    ZonedDateTime from, ZonedDateTime to, int limit) {
        var filters = new ArrayList<org.bson.conversions.Bson>();
        filters.add(com.mongodb.client.model.Filters.eq("project_id", projectId));
        if (subjectId != null && !subjectId.isBlank()) {
            filters.add(com.mongodb.client.model.Filters.eq("subject_id", subjectId));
        }
        if (type != null && !type.isBlank()) {
            filters.add(com.mongodb.client.model.Filters.eq("type", type));
        }
        if (from != null) filters.add(com.mongodb.client.model.Filters.gte("at", from));
        if (to != null) filters.add(com.mongodb.client.model.Filters.lte("at", to));
        var query = new core.framework.mongo.Query();
        query.filter = com.mongodb.client.model.Filters.and(filters);
        query.sort = com.mongodb.client.model.Sorts.descending("at");
        query.limit = limit;
        return collection.find(query);
    }

    @Override
    public boolean exists(String projectId, String subjectId, String type, String key, String value, ZonedDateTime at) {
        var filters = new ArrayList<org.bson.conversions.Bson>();
        filters.add(com.mongodb.client.model.Filters.eq("project_id", projectId));
        filters.add(com.mongodb.client.model.Filters.eq("subject_id", subjectId));
        filters.add(com.mongodb.client.model.Filters.eq("at", at));
        filters.add(com.mongodb.client.model.Filters.eq("type", type));
        filters.add(com.mongodb.client.model.Filters.eq("value", value));
        filters.add(key == null
            ? com.mongodb.client.model.Filters.exists("key", false)
            : com.mongodb.client.model.Filters.eq("key", key));
        return collection.count(com.mongodb.client.model.Filters.and(filters)) > 0;
    }

    @Override
    public void insert(ProjectSubjectEvent event) {
        collection.insert(event);
    }

    @Override
    public void deleteBySubject(String subjectId) {
        collection.delete(com.mongodb.client.model.Filters.eq("subject_id", subjectId));
    }
}
