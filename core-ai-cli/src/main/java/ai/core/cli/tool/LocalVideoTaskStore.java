package ai.core.cli.tool;

import ai.core.utils.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The local counterpart of the sandbox's per-session "one video task at a time" guard. A CLI tool call is one
 * process, so the pending video id is kept on disk between the submit and the poll.
 *
 * @author stephen
 */
final class LocalVideoTaskStore {
    private final Path file;

    LocalVideoTaskStore(Path workspace) {
        this.file = workspace.resolve(".core-ai/media/videos/pending.json");
    }

    String pending() {
        if (!Files.isRegularFile(file)) return null;
        try {
            var payload = JsonUtil.fromJson(Map.class, Files.readString(file, StandardCharsets.UTF_8));
            var videoId = payload.get("video_id");
            return videoId instanceof String value && !value.isBlank() ? value : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    void pending(String videoId) {
        if (videoId == null || videoId.isBlank()) return;
        try {
            var parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, JsonUtil.toJson(Map.of("video_id", videoId)), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // a lost guard is better than a failed generation
        }
    }

    void clear(String videoId) {
        if (videoId == null || !videoId.equals(pending())) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // the next terminal poll clears it
        }
    }
}
