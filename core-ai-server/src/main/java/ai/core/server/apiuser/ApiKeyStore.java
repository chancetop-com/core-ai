package ai.core.server.apiuser;

import ai.core.server.domain.ApiKey;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * API key persistence. Kept as an interface so callers do not depend on MongoCollection and the
 * storage can move to PostgreSQL with an explicit key_hash lookup.
 *
 * @author stephen
 */
public interface ApiKeyStore {
    Optional<ApiKey> get(String id);

    Optional<ApiKey> findByKeyHash(String keyHash);

    List<ApiKey> listByUser(String userId);

    List<ApiKey> listActiveManageKeys(String userId);

    void insert(ApiKey apiKey);

    void updateExpiresAt(String id, ZonedDateTime expiresAt);

    void revoke(String id, ZonedDateTime revokedAt);

    void markStatus(String id, String status, ZonedDateTime revokedAt);

    void updateLastUsedAt(String id, ZonedDateTime lastUsedAt);
}
