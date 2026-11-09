package ai.core.server.settings;

import ai.core.server.domain.SystemSettings;

import java.util.Optional;

/**
 * System settings are a small singleton/override document set. The interface keeps callers free of
 * MongoCollection so the storage can move to PostgreSQL independently.
 *
 * @author stephen
 */
public interface SystemSettingsStore {
    Optional<SystemSettings> get(String id);

    void insert(SystemSettings entity);

    void replace(SystemSettings entity);

    void delete(String id);
}
