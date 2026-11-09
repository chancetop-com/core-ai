package ai.core.server.user;

import ai.core.server.domain.User;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * User persistence. Reads are explicit methods so callers no longer depend on Mongo filters; writes
 * use a load/mutate/diff patch (Mongo) or a row-locked replace (PostgreSQL) to keep concurrent
 * quota/favorite/last-login updates from clobbering each other.
 *
 * @author stephen
 */
public interface UserStore {
    Optional<User> get(String id);

    List<User> listByIds(Collection<String> ids);

    List<User> listAll();

    List<User> listByUserType(String userType);

    List<User> listInternalAndApiUsers();

    List<User> listUsersWithEmail(int limit);

    List<String> listUserIdsByType(String userType, int limit);

    Optional<User> findByApiKey(String apiKey);

    Optional<User> findByOwnerAndExternalId(String ownerId, String externalId);

    void insert(User user);

    void replace(User user);

    void delete(String id);

    boolean update(String id, Consumer<User> mutator);

    void incrementQuotaUsage(String userId, long inputTokens, long outputTokens);

    void resetQuota(String userId, ZonedDateTime now);
}
