package ai.core.cli.memory.sync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans the local {@code .core-ai/} file tree, computes SHA-256 hashes,
 * and produces a three-way diff against Neo4j state and local sync metadata.
 */
class FileHashDiff {

    /**
     * Computes SHA-256 hash of a file's content. Returns empty string for empty files.
     */
    static String hash(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) return "";
            var digest = MessageDigest.getInstance("SHA-256");
            return bytesToHex(digest.digest(bytes));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to hash file: " + file, e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Performs a three-way diff:
     *
     * <pre>
     *   local files  ──┐
     *   Neo4j entries ─┼──► toUpsert (new/changed local files)
     *   sync-meta     ─┘    toDelete (local files gone, Neo4j still has them)
     *                       newSyncMeta (updated local hash map for .sync-meta)
     * </pre>
     *
     * <p>Never deletes from Neo4j if the file exists locally (local-wins policy).
     */
    DiffResult diff(Path coreAiDir, List<Neo4jSyncClient.MemoryFileEntry> neo4jEntries,
                    SyncMeta syncMeta, MemorySyncConfig config) {
        var localFiles = scanLocal(coreAiDir);
        var neo4jByRelPath = indexByRelativePath(neo4jEntries);
        Path workspace = coreAiDir.getParent(); // .core-ai/ → workspace root

        var toUpsert = new ArrayList<Neo4jSyncClient.MemoryFileEntry>();
        var toDelete = new ArrayList<Neo4jSyncClient.MemoryFileEntry>();
        var newMeta = new HashMap<String, String>();

        Set<String> allPaths = new HashSet<>();
        allPaths.addAll(localFiles.keySet());
        allPaths.addAll(neo4jByRelPath.keySet());

        String system = config.system();
        String project = workspace.getFileName().toString();

        for (var relPath : allPaths) {
            Path localFile = localFiles.get(relPath);
            Neo4jSyncClient.MemoryFileEntry neoEntry = neo4jByRelPath.get(relPath);

            if (localFile != null) {
                // file exists locally
                String localHash = hash(localFile);
                newMeta.put(relPath, localHash);

                if (neoEntry == null) {
                    // NEW: exists locally, not in Neo4j → upsert
                    toUpsert.add(entryFromFile(system, project, localFile, relPath, localHash));
                } else if (!localHash.equals(neoEntry.contentHash())) {
                    // CHANGED: local hash differs from Neo4j → upsert
                    toUpsert.add(entryFromFile(system, project, localFile, relPath, localHash));
                }
                // else: hash matches → skip (already synced)
            } else {
                // file does NOT exist locally, but exists in Neo4j
                String oldMetaHash = syncMeta.fileHashes().get(relPath);
                if (oldMetaHash != null) {
                    // Was previously synced, now deleted locally → delete from Neo4j
                    toDelete.add(neoEntry);
                    // removed from sync-meta (not added to newMeta)
                }
                // else: never synced by us, keep Neo4j entry (created on another machine)
            }
        }

        return new DiffResult(toUpsert, toDelete, newMeta);
    }

    /**
     * Scans .core-ai/ recursively, returning relative-path → absolute-path.
     * Skips hidden files (.DS_Store, .lock, .sync-meta, etc.).
     */
    private Map<String, Path> scanLocal(Path coreAiDir) {
        var files = new HashMap<String, Path>();
        try (var stream = Files.walk(coreAiDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .forEach(p -> {
                        String relPath = coreAiDir.relativize(p).toString();
                        files.put(relPath, p);
                    });
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan .core-ai/ directory: " + coreAiDir, e);
        }
        return files;
    }

    /**
     * Indexes Neo4j entries by their relative path within .core-ai/.
     */
    private Map<String, Neo4jSyncClient.MemoryFileEntry> indexByRelativePath(
            List<Neo4jSyncClient.MemoryFileEntry> entries) {
        var index = new HashMap<String, Neo4jSyncClient.MemoryFileEntry>();
        for (var entry : entries) {
            // path format: ".core-ai/{relativePath}" → strip prefix
            String path = entry.path();
            if (path.startsWith(".core-ai/")) {
                String relPath = path.substring(".core-ai/".length());
                index.put(relPath, entry);
            }
        }
        return index;
    }

    private Neo4jSyncClient.MemoryFileEntry entryFromFile(String system, String project,
                                                           Path file, String relPath,
                                                           String hash) {
        try {
            String content = Files.readString(file);
            return new Neo4jSyncClient.MemoryFileEntry(
                    system, project, ".core-ai/" + relPath, content, hash, null);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file for Neo4j entry: " + file, e);
        }
    }

    /**
     * Result of the three-way diff: what to merge into Neo4j, what to delete,
     * and what to write to the local sync-meta file.
     */
    record DiffResult(List<Neo4jSyncClient.MemoryFileEntry> toUpsert,
                      List<Neo4jSyncClient.MemoryFileEntry> toDelete,
                      Map<String, String> newSyncMeta) {
    }
}
