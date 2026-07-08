package ai.core.server.domain.migration;

import com.mongodb.MongoCommandException;
import core.framework.inject.Inject;
import core.framework.mongo.Mongo;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        var migrations = migrations();
        validateUniqueVersions(migrations);
        var applied = schemaVersionCollection.find(com.mongodb.client.model.Filters.exists("_id"))
            .stream()
            .map(v -> v.id)
            .collect(Collectors.toSet());
        Set<String> runInThisSession = new HashSet<>();

        for (var migration : migrations) {
            if (applied.contains(migration.version()) || runInThisSession.contains(migration.version())) {
                LOGGER.info("skip migration, version={}, description={}", migration.version(), migration.description());
                continue;
            }
            LOGGER.info("run migration, version={}, description={}", migration.version(), migration.description());
            try {
                migration.migrate(mongo);
            } catch (MongoCommandException e) {
                if (e.getErrorCode() == 86) {
                    LOGGER.warn("index conflict during migration, version={}, message={}", migration.version(), e.getErrorMessage());
                } else {
                    throw e;
                }
            }

            var version = new SchemaVersion();
            version.id = migration.version();
            version.description = migration.description();
            version.appliedAt = ZonedDateTime.now();
            schemaVersionCollection.insert(version);
            runInThisSession.add(migration.version());

            LOGGER.info("migration completed, version={}", migration.version());
        }
    }

    // duplicate versions make later migrations silently skip forever (seen twice on dev) — fail fast at startup
    private void validateUniqueVersions(List<SchemaMigration> migrations) {
        Set<String> versions = new HashSet<>();
        for (var migration : migrations) {
            if (!versions.add(migration.version())) {
                throw new Error("duplicate schema migration version, assign a new unique version: version=" + migration.version() + ", migration=" + migration.getClass().getSimpleName());
            }
        }
    }

    private List<SchemaMigration> migrations() {
        return List.of(
            new SchemaMigrationVInitialize(),
            new SchemaMigrationVDefaultAgent(),
            new SchemaMigrationVTraceIndexes(),
            new SchemaMigrationVSkillIndexes(),
            new SchemaMigrationVSystemPromptIndexes(),
            new SchemaMigrationVServiceApiIndexes(),
            new SchemaMigrationVMigrateToolIdsToTools(),
            new SchemaMigrationVChatMessageIndexes(),
            new SchemaMigrationVChatSessionIndexes(),
            new SchemaMigrationVChatSessionSourceIndexes(),
            new SchemaMigrationVUsersIndexes(),
            new SchemaMigrationVFileRecordsTTL(),
            new SchemaMigrationVTriggerIndexes(),
            new SchemaMigrationVDatasetIndexes(),
            new SchemaMigrationVDatasetRecordUserIndex(),
            new SchemaMigrationVTraceAccountScopeIndexes(),
            new SchemaMigrationVFileShareTokenIndex(),
            new SchemaMigrationVFixShareTokenIndex(),
            new SchemaMigrationVWorkflowIndexes(),
            new SchemaMigrationVWorkflowDefinitionIndexes(),
            new SchemaMigrationVWorkflowRunIndexes(),
            new SchemaMigrationVWorkflowPreviewIndexFix(),
            new SchemaMigrationVWorkflowPreviewRunTTL(),
            new SchemaMigrationVBumpDefaultMaxTurns(),
            new SchemaMigrationVMemoryIndexes(),
            new SchemaMigrationVTraceAgentIdIndex(),
            new SchemaMigrationVTraceListFilterIndexes(),
            new SchemaMigrationVAgentRunTraceIndex(),
            new SchemaMigrationVChatSessionCreatedAtIndex(),
            new SchemaMigrationVWorkflowPublicIndex(),
            new SchemaMigrationVWorkflowParentRunIndex(),
            new SchemaMigrationVWorkflowVisibilityStatusIndex(),
            new SchemaMigrationVIssueReporterAgent(),
            new SchemaMigrationVToolRegistryTypeNameIndex(),
            new SchemaMigrationVGatewayModelProviderIndex(),
            new SchemaMigrationVGatewayModelModelIdIndex(),
            new SchemaMigrationVMemoryLayerIndex(),
            new SchemaMigrationVSandboxSnapshotIndexes(),
            new SchemaMigrationVTraceMemoryConsolidationIndexes(),
            new SchemaMigrationVBackgroundTasks(),
            new SchemaMigrationVTraceDailyStats(),
            new SchemaMigrationVTraceDailyStatsAgent(),
            new SchemaMigrationVSessionFeedbackIndexes(),
            new SchemaMigrationVMemoryExperimentIndexes()
        );
    }
}
