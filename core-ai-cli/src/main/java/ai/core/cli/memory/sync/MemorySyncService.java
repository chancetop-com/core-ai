package ai.core.cli.memory.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Orchestrates Neo4j-backed memory sync at startup (RESTORE) and shutdown (BACKUP).
 *
 * <h3>RESTORE (startup)</h3>
 * <p>Pulls files from Neo4j that don't exist locally. Never overwrites existing
 * local files — local always wins. Called before {@code MdMemoryProvider.load()}
 * so the agent sees accumulated knowledge from previous sessions.
 *
 * <h3>BACKUP (shutdown)</h3>
 * <p>Three-way diff: local files vs Neo4j vs sync-meta. Pushes new and changed
 * files to Neo4j, deletes removed files. Writes updated sync-meta. All in a
 * single Neo4j transaction.
 *
 * <p>Files are identified by composite key ({@code system}, {@code project}, {@code path}),
 * enabling targeted queries without string parsing.
 *
 * <p>Both operations silently skip if Neo4j is unreachable — the agent can still
 * work normally, just without cross-session memory accumulation.
 */
public class MemorySyncService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemorySyncService.class);

    /**
     * Extracts the path relative to .core-ai/ from the Neo4j path field.
     * {@code ".core-ai/knowledge/project/foo.md"} → {@code "knowledge/project/foo.md"}.
     */
    private static String stripCoreAiPrefix(String path) {
        if (path.startsWith(".core-ai/")) {
            return path.substring(".core-ai/".length());
        }
        return path;
    }

    private final MemorySyncConfig config;
    private final FileHashDiff diff;

    public MemorySyncService(MemorySyncConfig config) {
        this.config = config;
        this.diff = new FileHashDiff();
    }

    /**
     * Pulls files from Neo4j that don't exist locally.
     * Never overwrites existing files (local-wins).
     *
     * @param workspace the workspace root directory
     */
    public void restore(Path workspace) {
        if (!config.enabled()) return;
        Path coreAiDir = workspace.resolve(".core-ai");
        ensureCoreAiDir(coreAiDir);

        try (var client = new Neo4jSyncClient(config)) {
            var entries = client.fetchAll(config.system(),
                    workspace.getFileName().toString());
            if (entries.isEmpty()) {
                LOGGER.debug("No Neo4j memory files found for system={} project={}",
                        config.system(), workspace.getFileName());
                return;
            }

            int created = 0;
            int skipped = 0;
            for (var entry : entries) {
                String relPath = stripCoreAiPrefix(entry.path());
                Path localFile = coreAiDir.resolve(relPath);
                if (Files.exists(localFile)) {
                    skipped++;
                    continue; // local wins — never overwrite
                }
                writeFile(localFile, entry.content());
                created++;
            }

            LOGGER.info("RESTORE: {} files created, {} skipped (local wins) from Neo4j",
                    created, skipped);
        } catch (Exception e) {
            LOGGER.warn("RESTORE failed (Neo4j unreachable?): {}. Agent will run without prior memory.",
                    e.getMessage());
        }
    }

    /**
     * Pushes local file changes to Neo4j using a three-way diff.
     *
     * @param workspace the workspace root directory
     */
    public void backup(Path workspace) {
        if (!config.enabled()) return;
        Path coreAiDir = workspace.resolve(".core-ai");
        if (!Files.isDirectory(coreAiDir)) {
            LOGGER.debug("No .core-ai/ directory, skipping BACKUP");
            return;
        }

        try (var client = new Neo4jSyncClient(config)) {
            var neo4jEntries = client.fetchAll(config.system(),
                    workspace.getFileName().toString());
            var syncMeta = SyncMeta.load(coreAiDir);
            var result = diff.diff(coreAiDir, neo4jEntries, syncMeta, config);

            if (result.toUpsert().isEmpty() && result.toDelete().isEmpty()) {
                LOGGER.debug("BACKUP: no changes detected, skipping");
                return;
            }

            var batch = new Neo4jSyncClient.BatchChanges(result.toUpsert(), result.toDelete());
            client.applyBatch(batch);
            syncMeta.save(coreAiDir, result.newSyncMeta());

            LOGGER.info("BACKUP: {} files upserted, {} deleted to Neo4j",
                    result.toUpsert().size(), result.toDelete().size());
        } catch (Exception e) {
            LOGGER.warn("BACKUP failed (Neo4j unreachable?): {}. Local files are preserved, "
                        + "changes will be pushed on next successful BACKUP.", e.getMessage());
            // Local .sync-meta is NOT updated on failure — next BACKUP will retry
        }
    }

    private void ensureCoreAiDir(Path coreAiDir) {
        try {
            Files.createDirectories(coreAiDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create .core-ai/ directory: " + coreAiDir, e);
        }
    }

    private void writeFile(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write restored file: " + file, e);
        }
    }
}
