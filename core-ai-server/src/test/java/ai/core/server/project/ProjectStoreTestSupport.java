package ai.core.server.project;

import ai.core.server.domain.Project;
import core.framework.mongo.MongoCollection;

/**
 * @author stephen
 */
final class ProjectStoreTestSupport {
    static ProjectStore store(MongoCollection<Project> collection) {
        var store = new MongoProjectStore();
        store.collection = collection;
        return store;
    }

    private ProjectStoreTestSupport() {
    }
}
