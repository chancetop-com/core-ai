package ai.core.trace.domain.migration;

import core.framework.inject.Inject;
import core.framework.mongo.Mongo;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.core.trace.domain.SchemaVersion;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Xander
 */
public class SchemaMigrationManager {
    private final Logger logger = LoggerFactory.getLogger(SchemaMigrationManager.class);

    @Inject
    MongoCollection<SchemaVersion> schemaVersionCollection;
    @Inject
    Mongo mongo;

    public void migrate() {
        Set<String> appliedVersions = schemaVersionCollection.find(new Query()).stream()
            .map(v -> v.version)
            .collect(Collectors.toSet());

        for (SchemaMigration migration : migrations()) {
            if (appliedVersions.contains(migration.version())) {
                continue;
            }
            logger.info("applying migration: {} - {}", migration.version(), migration.description());
            migration.migrate(mongo);
            recordMigration(migration);
        }
    }

    private void recordMigration(SchemaMigration migration) {
        var version = new SchemaVersion();
        version.id = migration.version();
        version.version = migration.version();
        version.description = migration.description();
        version.appliedAt = ZonedDateTime.now();
        schemaVersionCollection.insert(version);
    }

    private List<SchemaMigration> migrations() {
        return List.of(
            new SchemaMigrationVInitialize()
        );
    }
}
