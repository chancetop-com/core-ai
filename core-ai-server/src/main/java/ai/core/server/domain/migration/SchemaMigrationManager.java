package ai.core.server.domain.migration;

import core.framework.inject.Inject;
import core.framework.mongo.Mongo;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class SchemaMigrationManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaMigrationManager.class);

    @Inject
    Mongo mongo;

    @Inject
    MongoCollection<SchemaVersion> schemaVersionCollection;

    public void migrate() {
        var applied = schemaVersionCollection.find(com.mongodb.client.model.Filters.exists("_id"))
            .stream()
            .map(v -> v.id)
            .collect(Collectors.toSet());

        for (var migration : migrations()) {
            if (applied.contains(migration.version())) {
                LOGGER.info("skip migration, version={}, description={}", migration.version(), migration.description());
                continue;
            }
            LOGGER.info("run migration, version={}, description={}", migration.version(), migration.description());
            migration.migrate(mongo);

            var version = new SchemaVersion();
            version.id = migration.version();
            version.description = migration.description();
            version.appliedAt = ZonedDateTime.now();
            schemaVersionCollection.insert(version);

            LOGGER.info("migration completed, version={}", migration.version());
        }
    }

    private List<SchemaMigration> migrations() {
        return List.of(
            new SchemaMigrationVInitialize(),
            new SchemaMigrationVDefaultAgent(),
            new SchemaMigrationVTraceIndexes(),
            new SchemaMigrationVSkillIndexes()
        );
    }
}
