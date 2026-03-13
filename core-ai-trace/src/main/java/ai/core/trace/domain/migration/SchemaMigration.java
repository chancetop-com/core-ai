package ai.core.trace.domain.migration;

import core.framework.mongo.Mongo;

/**
 * @author Xander
 */
public interface SchemaMigration {
    String version();

    String description();

    void migrate(Mongo mongo);
}
