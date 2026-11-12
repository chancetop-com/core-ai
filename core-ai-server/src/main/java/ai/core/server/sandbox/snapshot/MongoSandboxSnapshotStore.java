package ai.core.server.sandbox.snapshot;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public class MongoSandboxSnapshotStore implements SandboxSnapshotStore {
    public static MongoSandboxSnapshotStore of(MongoCollection<SandboxSnapshotDoc> collection) {
        var store = new MongoSandboxSnapshotStore();
        store.snapshotCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<SandboxSnapshotDoc> snapshotCollection;

    @Override
    public void insert(SandboxSnapshotDoc doc) {
        snapshotCollection.insert(doc);
    }

    @Override
    public SandboxSnapshotDoc findLatestAvailable(String sessionId) {
        var query = new Query();
        query.filter = Filters.and(
            Filters.eq("session_id", sessionId),
            Filters.eq("status", SandboxSnapshotDoc.STATUS_AVAILABLE));
        query.sort = Sorts.descending("created_at");
        query.limit = 1;
        var docs = snapshotCollection.find(query);
        return docs.isEmpty() ? null : docs.getFirst();
    }

    @Override
    public List<SandboxSnapshotDoc> listExpired(ZonedDateTime now, int limit) {
        var query = new Query();
        query.filter = Filters.lt("expires_at", now);
        query.limit = limit;
        return snapshotCollection.find(query);
    }

    @Override
    public List<SandboxSnapshotDoc> listBySession(String sessionId) {
        var query = new Query();
        query.filter = Filters.eq("session_id", sessionId);
        return snapshotCollection.find(query);
    }

    @Override
    public boolean markAvailable(String id) {
        return snapshotCollection.update(
            Filters.and(Filters.eq("_id", id), Filters.eq("status", SandboxSnapshotDoc.STATUS_UPLOADING)),
            Updates.set("status", SandboxSnapshotDoc.STATUS_AVAILABLE)) == 1;
    }

    @Override
    public void tombstone(String id) {
        snapshotCollection.update(Filters.eq("_id", id), Updates.combine(
            Updates.set("status", SandboxSnapshotDoc.STATUS_DELETED),
            Updates.set("expires_at", ZonedDateTime.now())));
    }

    @Override
    public void delete(String id) {
        snapshotCollection.delete(id);
    }
}

