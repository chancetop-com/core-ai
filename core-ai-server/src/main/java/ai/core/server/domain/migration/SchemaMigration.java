package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;

/**
 * @author stephen
 */
public interface SchemaMigration {
    String version();

    String description();

    void migrate(Mongo mongo);
}
