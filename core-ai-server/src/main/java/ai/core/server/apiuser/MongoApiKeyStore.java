package ai.core.server.apiuser;

import ai.core.server.domain.ApiKey;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoApiKeyStore implements ApiKeyStore {
    @Inject
    MongoCollection<ApiKey> collection;

    @Override
    public Optional<ApiKey> get(String id) {
        return collection.get(id);
    }

    @Override
    public Optional<ApiKey> findByKeyHash(String keyHash) {
        return collection.findOne(Filters.eq("key_hash", keyHash));
    }

    @Override
    public List<ApiKey> listByUser(String userId) {
        var query = new Query();
        query.filter = Filters.eq("user_id", userId);
        query.sort = Sorts.descending("created_at");
        return collection.find(query);
    }

    @Override
    public List<ApiKey> listActiveManageKeys(String userId) {
        var query = new Query();
        query.filter = Filters.and(
                Filters.eq("user_id", userId),
                Filters.eq("scope", ApiUserKeyService.SCOPE_MANAGE),
                Filters.eq("status", "active"));
        return collection.find(query);
    }

    @Override
    public void insert(ApiKey apiKey) {
        collection.insert(apiKey);
    }

    @Override
    public void updateExpiresAt(String id, ZonedDateTime expiresAt) {
        collection.update(Filters.eq("_id", id), Updates.set("expires_at", expiresAt));
    }

    @Override
    public void revoke(String id, ZonedDateTime revokedAt) {
        collection.update(Filters.eq("_id", id), Updates.combine(
                Updates.set("status", "revoked"),
                Updates.set("revoked_at", revokedAt)));
    }

    @Override
    public void markStatus(String id, String status, ZonedDateTime revokedAt) {
        collection.update(Filters.eq("_id", id), Updates.combine(
                Updates.set("status", status),
                Updates.set("revoked_at", revokedAt)));
    }

    @Override
    public void updateLastUsedAt(String id, ZonedDateTime lastUsedAt) {
        collection.update(Filters.eq("_id", id), Updates.set("last_used_at", lastUsedAt));
    }
}
