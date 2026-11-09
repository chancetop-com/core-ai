package ai.core.server.apiuser;

import ai.core.server.domain.ApiKey;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgApiKeyStore implements ApiKeyStore {
    private static final String TABLE = "app.api_keys";
    private static final String SELECT = "SELECT id, key_hash, key_prefix, user_id, scope, metadata::text AS metadata, "
            + "status, expires_at, created_at, last_used_at, revoked_at FROM " + TABLE;
    private static ApiKey toApiKey(PgApiKeyRow row) {
        var apiKey = new ApiKey();
        apiKey.id = row.id;
        apiKey.keyHash = row.keyHash;
        apiKey.keyPrefix = row.keyPrefix;
        apiKey.userId = row.userId;
        apiKey.scope = row.scope;
        apiKey.metadata = row.metadata != null ? JsonUtil.fromJson(METADATA_TYPE, row.metadata) : null;
        apiKey.status = row.status;
        apiKey.expiresAt = row.expiresAt;
        apiKey.createdAt = row.createdAt;
        apiKey.lastUsedAt = row.lastUsedAt;
        apiKey.revokedAt = row.revokedAt;
        return apiKey;
    }

    private static final TypeReference<Map<String, String>> METADATA_TYPE = new TypeReference<>() { };

    @Inject
    Database database;

    @Override
    public Optional<ApiKey> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgApiKeyRow.class, id).map(PgApiKeyStore::toApiKey);
    }

    @Override
    public Optional<ApiKey> findByKeyHash(String keyHash) {
        return database.selectOne(SELECT + " WHERE key_hash = ?", PgApiKeyRow.class, keyHash).map(PgApiKeyStore::toApiKey);
    }

    @Override
    public List<ApiKey> listByUser(String userId) {
        return database.select(SELECT + " WHERE user_id = ? ORDER BY created_at DESC", PgApiKeyRow.class, userId)
                .stream().map(PgApiKeyStore::toApiKey).toList();
    }

    @Override
    public List<ApiKey> listActiveManageKeys(String userId) {
        return database.select(SELECT + " WHERE user_id = ? AND scope = ? AND status = 'active'",
                        PgApiKeyRow.class, userId, ApiUserKeyService.SCOPE_MANAGE)
                .stream().map(PgApiKeyStore::toApiKey).toList();
    }

    @Override
    public void insert(ApiKey apiKey) {
        database.execute("INSERT INTO " + TABLE + " (id, key_hash, key_prefix, user_id, scope, metadata, status, "
                        + "expires_at, created_at, last_used_at, revoked_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)",
                params(apiKey));
    }

    @Override
    public void updateExpiresAt(String id, ZonedDateTime expiresAt) {
        database.execute("UPDATE " + TABLE + " SET expires_at = ? WHERE id = ?", expiresAt, id);
    }

    @Override
    public void revoke(String id, ZonedDateTime revokedAt) {
        database.execute("UPDATE " + TABLE + " SET status = ?, revoked_at = ? WHERE id = ?", "revoked", revokedAt, id);
    }

    @Override
    public void markStatus(String id, String status, ZonedDateTime revokedAt) {
        database.execute("UPDATE " + TABLE + " SET status = ?, revoked_at = ? WHERE id = ?", status, revokedAt, id);
    }

    @Override
    public void updateLastUsedAt(String id, ZonedDateTime lastUsedAt) {
        database.execute("UPDATE " + TABLE + " SET last_used_at = ? WHERE id = ?", lastUsedAt, id);
    }

    private Object[] params(ApiKey apiKey) {
        return new Object[]{
            apiKey.id, apiKey.keyHash, apiKey.keyPrefix, apiKey.userId, apiKey.scope,
            apiKey.metadata != null ? JsonUtil.toJson(apiKey.metadata) : null,
            apiKey.status, apiKey.expiresAt, apiKey.createdAt, apiKey.lastUsedAt, apiKey.revokedAt};
    }

}
