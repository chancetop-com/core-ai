package ai.core.cli.memory.sync;

import ai.core.utils.JsonUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Local sync metadata stored in {@code .core-ai/.sync-meta}.
 *
 * <p>Tracks the last successful sync state so RESTORE can detect
 * local-wins conflicts without overwriting un-synced changes.
 */
record SyncMeta(Map<String, String> fileHashes) {

    static SyncMeta empty() {
        return new SyncMeta(new HashMap<>());
    }

    static SyncMeta load(Path coreAiDir) {
        Path metaFile = coreAiDir.resolve(".sync-meta");
        if (!Files.exists(metaFile)) {
            return empty();
        }
        try {
            String json = Files.readString(metaFile);
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = JsonUtil.fromJson(Map.class, json);
            if (raw == null) return empty();
            @SuppressWarnings("unchecked")
            Map<String, String> hashes = (Map<String, String>) raw.getOrDefault("fileHashes",
                    new HashMap<>());
            return new SyncMeta(new HashMap<>(hashes));
        } catch (IOException e) {
            return empty();
        }
    }

    void save(Path coreAiDir, Map<String, String> newHashes) {
        var data = new HashMap<String, Object>();
        data.put("fileHashes", newHashes);
        try {
            Files.writeString(coreAiDir.resolve(".sync-meta"), JsonUtil.toJson(data));
        } catch (IOException e) {
            throw new RuntimeException("Failed to write .sync-meta: " + coreAiDir, e);
        }
    }
}
