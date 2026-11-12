package ai.core.server.workflow;

import ai.core.server.domain.WorkflowPublishedVersion;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoWorkflowVersionStore implements WorkflowVersionStore {
    public static MongoWorkflowVersionStore of(MongoCollection<WorkflowPublishedVersion> collection) {
        var store = new MongoWorkflowVersionStore();
        store.versionCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<WorkflowPublishedVersion> versionCollection;

    @Override
    public Optional<WorkflowPublishedVersion> get(String id) {
        return versionCollection.get(id);
    }

    @Override
    public void insert(WorkflowPublishedVersion version) {
        versionCollection.insert(version);
    }

    @Override
    public List<WorkflowPublishedVersion> listNonPreview(String workflowId) {
        var query = new Query();
        query.filter = Filters.and(Filters.eq("workflow_id", workflowId), Filters.ne("preview", Boolean.TRUE));
        query.sort = Sorts.descending("version");
        return versionCollection.find(query);
    }

    @Override
    public List<WorkflowPublishedVersion> listAll(String workflowId) {
        return versionCollection.find(Filters.eq("workflow_id", workflowId));
    }

    @Override
    public int deletePreviewOlderThan(ZonedDateTime cutoff) {
        return (int) versionCollection.delete(Filters.and(
            Filters.eq("preview", Boolean.TRUE), Filters.lt("published_at", cutoff)));
    }
}
