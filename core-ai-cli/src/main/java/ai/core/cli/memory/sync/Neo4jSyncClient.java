package ai.core.cli.memory.sync;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper around the Neo4j Java driver for memory file CRUD.
 *
 * <h3>Graph model</h3>
 * <pre>
 *   (Agent) ──[:HAS_ROOT]──→ (Directory) ──[:CONTAINS]──→ (Directory) ──[:CONTAINS]──→ (File)
 *      unique on                unique on                     (recursive)                 unique on
 *   (system, project)           (path)                                                  (system, project, path)
 * </pre>
 *
 * <p>Agent connects to files through the directory tree — no direct Agent→File edge.
 * Files can also be found directly via {@code WHERE f.system=$s AND f.project=$p}.
 *
 * <p>All nodes and relationships are created with MERGE — fully idempotent.
 * File deletes use DETACH DELETE (removes CONTAINS from parent directory).
 */
class Neo4jSyncClient implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(Neo4jSyncClient.class);

    private static List<String> buildDirectoryPaths(String filePath) {
        List<String> dirs = new ArrayList<>();
        int start = 0;
        while (true) {
            int slash = filePath.indexOf('/', start);
            if (slash < 0) break;
            dirs.add(filePath.substring(0, slash));
            start = slash + 1;
        }
        return dirs;
    }

    private final Driver driver;

    Neo4jSyncClient(MemorySyncConfig config) {
        this.driver = GraphDatabase.driver(config.uri(),
                AuthTokens.basic(config.username(), config.password()));
        ensureConstraints();
    }

    List<MemoryFileEntry> fetchAll(String system, String project) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run(
                        "MATCH (f:File) WHERE f.system = $system AND f.project = $project "
                        + "RETURN f.system, f.project, f.path, f.content, f.contentHash, f.updatedAt",
                        Values.parameters("system", system, "project", project));
                var entries = new ArrayList<MemoryFileEntry>();
                while (result.hasNext()) {
                    var record = result.next();
                    entries.add(new MemoryFileEntry(
                            record.get("f.system").asString(),
                            record.get("f.project").asString(),
                            record.get("f.path").asString(),
                            record.get("f.content").asString(),
                            record.get("f.contentHash").asString(),
                            record.get("f.updatedAt").asZonedDateTime()));
                }
                return entries;
            });
        }
    }

    void applyBatch(BatchChanges changes) {
        try (var session = driver.session()) {
            session.executeWriteWithoutResult(tx -> {
                for (var entry : changes.merges) {
                    upsertFile(tx, entry);
                }
                for (var entry : changes.deletes) {
                    tx.run("MATCH (f:File {system: $system, project: $project, path: $path}) DETACH DELETE f",
                            Values.parameters(
                                    "system", entry.system,
                                    "project", entry.project,
                                    "path", entry.path));
                }
            });
        }
        LOGGER.debug("Neo4j batch applied: {} upserts, {} deletes",
                changes.merges.size(), changes.deletes.size());
    }

    private void upsertFile(TransactionContext tx, MemoryFileEntry entry) {
        // Agent
        tx.run("MERGE (a:Agent {system: $system, project: $project})",
                Values.parameters("system", entry.system(), "project", entry.project()));

        // Directory chain
        List<String> dirs = buildDirectoryPaths(entry.path());
        String prev = null;
        for (String dir : dirs) {
            tx.run("MERGE (d:Directory {path: $path})",
                    Values.parameters("path", dir));
            if (prev != null) {
                tx.run("MATCH (p:Directory {path: $prev}), (c:Directory {path: $curr}) "
                        + "MERGE (p)-[:CONTAINS]->(c)",
                        Values.parameters("prev", prev, "curr", dir));
            }
            prev = dir;
        }

        // File
        tx.run("MERGE (f:File {system: $system, project: $project, path: $path}) "
                + "SET f.content = $content, f.contentHash = $hash, f.updatedAt = $now",
                Values.parameters(
                        "system", entry.system(), "project", entry.project(), "path", entry.path(),
                        "content", entry.content(),
                        "hash", entry.contentHash(),
                        "now", ZonedDateTime.now()));

        // Agent → root directory
        if (!dirs.isEmpty()) {
            tx.run("MATCH (a:Agent {system: $system, project: $project}), "
                    + "(d:Directory {path: $root}) "
                    + "MERGE (a)-[:HAS_ROOT]->(d)",
                    Values.parameters("system", entry.system(), "project", entry.project(),
                            "root", dirs.get(0)));
        }

        // Last directory → File
        if (!dirs.isEmpty()) {
            tx.run("MATCH (d:Directory {path: $dir}), "
                    + "(f:File {system: $system, project: $project, path: $path}) "
                    + "MERGE (d)-[:CONTAINS]->(f)",
                    Values.parameters("dir", dirs.get(dirs.size() - 1),
                            "system", entry.system(), "project", entry.project(),
                            "path", entry.path()));
        }
    }

    private void ensureConstraints() {
        try (var session = driver.session()) {
            session.executeWriteWithoutResult(tx -> {
                tx.run("CREATE CONSTRAINT agent_unique IF NOT EXISTS "
                        + "FOR (a:Agent) REQUIRE (a.system, a.project) IS UNIQUE");
                tx.run("CREATE CONSTRAINT directory_path_unique IF NOT EXISTS "
                        + "FOR (d:Directory) REQUIRE d.path IS UNIQUE");
                tx.run("CREATE CONSTRAINT file_unique IF NOT EXISTS "
                        + "FOR (f:File) REQUIRE (f.system, f.project, f.path) IS UNIQUE");
                tx.run("DROP CONSTRAINT memory_file_unique IF EXISTS");
            });
        }
    }

    @Override
    public void close() {
        driver.close();
    }

    record MemoryFileEntry(String system, String project, String path,
                           String content, String contentHash, ZonedDateTime syncedAt) {
    }

    record BatchChanges(List<MemoryFileEntry> merges, List<MemoryFileEntry> deletes) {
        BatchChanges() {
            this(new ArrayList<>(), new ArrayList<>());
        }
    }
}
