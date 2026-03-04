package ai.core.server.migration;

import core.framework.mongo.Mongo;

/**
 * @author stephen
 */
public interface SchemaMigration {
    String version();

    String description();

    void migrate(Mongo mongo);
}
