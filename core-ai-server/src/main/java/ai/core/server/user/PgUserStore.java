package ai.core.server.user;

import ai.core.server.domain.User;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public class PgUserStore implements UserStore {
    private static final String TABLE = "app.users";
    private static final String SELECT = "SELECT id, name, email, api_key, role, status, user_type, owner_id, external_id, "
            + "created_at, last_login_at, data::text AS data FROM " + TABLE;

    private static User toUser(PgUserRow row) {
        return JsonUtil.fromJson(User.class, row.data);
    }

    private static String placeholders(int size) {
        return String.join(", ", java.util.Collections.nCopies(size, "?"));
    }

    @Inject
    Database database;

    @Override
    public Optional<User> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgUserRow.class, id).map(PgUserStore::toUser);
    }

    @Override
    public List<User> listByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        var values = new ArrayList<>(ids);
        return database.select(SELECT + " WHERE id IN (" + placeholders(values.size()) + ")", PgUserRow.class, values.toArray())
                .stream().map(PgUserStore::toUser).toList();
    }

    @Override
    public List<User> listAll() {
        return database.select(SELECT + " ORDER BY created_at", PgUserRow.class).stream().map(PgUserStore::toUser).toList();
    }

    @Override
    public List<User> listByUserType(String userType) {
        return database.select(SELECT + " WHERE user_type = ?", PgUserRow.class, userType)
                .stream().map(PgUserStore::toUser).toList();
    }

    @Override
    public List<User> listInternalAndApiUsers() {
        return database.select(SELECT + " WHERE user_type IN (?, ?) ORDER BY created_at", PgUserRow.class, "internal", "api")
                .stream().map(PgUserStore::toUser).toList();
    }

    @Override
    public List<User> listUsersWithEmail(int limit) {
        return database.select(SELECT + " WHERE email IS NOT NULL LIMIT ?", PgUserRow.class, limit)
                .stream().map(PgUserStore::toUser).toList();
    }

    @Override
    public List<String> listUserIdsByType(String userType, int limit) {
        return database.select("SELECT id FROM " + TABLE + " WHERE user_type = ? LIMIT ?", PgUserIdRow.class, userType, limit)
                .stream().map(row -> row.id).toList();
    }

    @Override
    public Optional<User> findByApiKey(String apiKey) {
        return database.selectOne(SELECT + " WHERE api_key = ?", PgUserRow.class, apiKey).map(PgUserStore::toUser);
    }

    @Override
    public Optional<User> findByOwnerAndExternalId(String ownerId, String externalId) {
        return database.selectOne(SELECT + " WHERE owner_id = ? AND external_id = ?", PgUserRow.class, ownerId, externalId)
                .map(PgUserStore::toUser);
    }

    @Override
    public void insert(User user) {
        database.execute("INSERT INTO " + TABLE + " (id, name, email, api_key, role, status, user_type, owner_id, external_id, "
                        + "created_at, last_login_at, data) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                user.id, user.name, user.email, user.apiKey, user.role, user.status, user.userType, user.ownerId,
                user.externalId, user.createdAt, user.lastLoginAt, JsonUtil.toJson(user));
    }

    @Override
    public void replace(User user) {
        write(user);
    }

    private void write(User user) {
        database.execute("UPDATE " + TABLE + " SET name = ?, email = ?, api_key = ?, role = ?, status = ?, user_type = ?, "
                        + "owner_id = ?, external_id = ?, created_at = ?, last_login_at = ?, data = ?::jsonb WHERE id = ?",
                user.name, user.email, user.apiKey, user.role, user.status, user.userType, user.ownerId,
                user.externalId, user.createdAt, user.lastLoginAt, JsonUtil.toJson(user), user.id);
    }

    @Override
    public void delete(String id) {
        database.execute("DELETE FROM " + TABLE + " WHERE id = ?", id);
    }

    @Override
    public boolean update(String id, Consumer<User> mutator) {
        try (var transaction = database.beginTransaction()) {
            var row = database.selectOne(SELECT + " WHERE id = ? FOR UPDATE", PgUserRow.class, id).orElse(null);
            if (row == null) {
                transaction.commit();
                return false;
            }
            var user = toUser(row);
            mutator.accept(user);
            write(user);
            transaction.commit();
            return true;
        }
    }

    @Override
    public void incrementQuotaUsage(String userId, long inputTokens, long outputTokens) {
        try (var transaction = database.beginTransaction()) {
            var row = database.selectOne(SELECT + " WHERE id = ? FOR UPDATE", PgUserRow.class, userId).orElse(null);
            if (row == null) {
                transaction.commit();
                return;
            }
            var user = toUser(row);
            if (inputTokens > 0 && user.quotaInputTokens != null && user.quotaInputTokens > 0) {
                user.quotaConsumedInputTokens = (user.quotaConsumedInputTokens != null ? user.quotaConsumedInputTokens : 0L) + inputTokens;
            }
            if (outputTokens > 0 && user.quotaOutputTokens != null && user.quotaOutputTokens > 0) {
                user.quotaConsumedOutputTokens = (user.quotaConsumedOutputTokens != null ? user.quotaConsumedOutputTokens : 0L) + outputTokens;
            }
            write(user);
            transaction.commit();
        }
    }

    @Override
    public void resetQuota(String userId, ZonedDateTime now) {
        try (var transaction = database.beginTransaction()) {
            var row = database.selectOne(SELECT + " WHERE id = ? FOR UPDATE", PgUserRow.class, userId).orElse(null);
            if (row == null) {
                transaction.commit();
                return;
            }
            var user = toUser(row);
            user.quotaConsumedInputTokens = 0L;
            user.quotaConsumedOutputTokens = 0L;
            user.quotaWindowStart = now;
            write(user);
            transaction.commit();
        }
    }
}
