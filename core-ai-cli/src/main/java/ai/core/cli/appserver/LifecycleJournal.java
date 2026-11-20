package ai.core.cli.appserver;

import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Low-frequency, append-only record of Task Run facts for one session. It is deliberately narrower
 * than the transcript: no model steps, chunks or tool payloads, only identities, state transitions
 * and terminal outcomes that a Watch needs to bind and recover.
 *
 * @author stephen
 */
final class LifecycleJournal implements AutoCloseable {
    private static final String FILE_SUFFIX = ".lifecycle.jsonl";

    static LifecycleJournal open(Path sessionsDir, String sessionId) {
        try {
            Files.createDirectories(sessionsDir);
            var journal = new LifecycleJournal(sessionsDir.resolve(sessionId + FILE_SUFFIX));
            journal.load();
            journal.channel = FileChannel.open(journal.file, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            return journal;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open lifecycle journal: " + sessionId, e);
        }
    }

    static void delete(Path sessionsDir, String sessionId) {
        try {
            Files.deleteIfExists(sessionsDir.resolve(sessionId + FILE_SUFFIX));
        } catch (IOException e) {
            // deletion is best-effort; the session files are already gone
        }
    }

    private static boolean belongsToRun(JsonNode record, String taskRunId) {
        if (taskRunId.equals(record.path("run").path("taskRunId").asText())) return true;
        return taskRunId.equals(record.path("ended").path("taskRunId").asText());
    }

    private final Path file;
    private final List<ObjectNode> records = new ArrayList<>();
    private FileChannel channel;
    private long seq;
    private String incarnation;
    private boolean closed;

    private LifecycleJournal(Path file) {
        this.file = file;
    }

    private void load() throws IOException {
        if (!Files.exists(file)) {
            incarnation = "inc-" + UUID.randomUUID();
            return;
        }
        truncatePartialTail();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            var line = reader.readLine();
            while (line != null) {
                parse(line);
                line = reader.readLine();
            }
        }
        if (incarnation == null) incarnation = "inc-" + UUID.randomUUID();
    }

    private void truncatePartialTail() throws IOException {
        var size = Files.size(file);
        if (size == 0) return;
        try (var fileChannel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var last = ByteBuffer.allocate(1);
            fileChannel.read(last, size - 1);
            if (last.get(0) == '\n') return;
            var data = Files.readAllBytes(file);
            int newline = -1;
            for (int i = data.length - 1; i >= 0; i--) {
                if (data[i] == '\n') {
                    newline = i;
                    break;
                }
            }
            fileChannel.truncate(newline < 0 ? 0 : newline + 1L);
        }
    }

    private void parse(String line) {
        if (line.isBlank()) return;
        try {
            var node = JsonUtil.OBJECT_MAPPER.readTree(line);
            if (!(node instanceof ObjectNode object)) return;
            records.add(object);
            seq = Math.max(seq, object.path("seq").asLong(0));
            if (incarnation == null) incarnation = object.path("incarnation").asText(null);
        } catch (IOException e) {
            // A damaged line is a real diagnostic; stop trusting replay rather than silently skip it.
            throw new UncheckedIOException("lifecycle journal is corrupted: " + file, e);
        }
    }

    synchronized ObjectNode append(String type, ObjectNode payload) {
        if (closed) throw new IllegalStateException("lifecycle journal is closed: " + file);
        var node = JsonUtil.OBJECT_MAPPER.createObjectNode();
        var next = seq + 1;
        node.put("seq", next);
        node.put("at", System.currentTimeMillis());
        node.put("incarnation", incarnation);
        node.put("type", type);
        node.setAll(payload);
        try {
            var buffer = StandardCharsets.UTF_8.encode(node + "\n");
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(false);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to append lifecycle journal: " + file, e);
        }
        seq = next;
        records.add(node);
        return node;
    }

    synchronized List<JsonNode> records() {
        return List.copyOf(records);
    }

    synchronized long seq() {
        return seq;
    }

    String incarnation() {
        return incarnation;
    }

    synchronized JsonNode lastEnded() {
        for (int i = records.size() - 1; i >= 0; i--) {
            var record = records.get(i);
            if ("run_ended".equals(record.path("type").asText())) return record;
        }
        return null;
    }

    synchronized JsonNode endedForRun(String taskRunId) {
        for (int i = records.size() - 1; i >= 0; i--) {
            var record = records.get(i);
            if (!"run_ended".equals(record.path("type").asText())) continue;
            if (taskRunId.equals(record.path("ended").path("taskRunId").asText())) return record;
        }
        return null;
    }

    /**
     * The run that was still open when the previous engine process stopped. A new engine cannot
     * inherit its execution; the source records it as interrupted and never replays its side effects.
     */
    synchronized JsonNode unfinishedRun() {
        for (int i = records.size() - 1; i >= 0; i--) {
            var record = records.get(i);
            var type = record.path("type").asText();
            if (!"run_state".equals(type) && !"run_registered".equals(type)) continue;
            var run = record.path("run");
            var taskRunId = run.path("taskRunId").asText(null);
            if (taskRunId == null) return null;
            if (endedForRun(taskRunId) != null) return null;
            return run;
        }
        return null;
    }

    synchronized JsonNode watchBind(String watchId) {
        for (int i = records.size() - 1; i >= 0; i--) {
            var record = records.get(i);
            var type = record.path("type").asText();
            if ("watch_unbind".equals(type) && watchId.equals(record.path("watchId").asText())) return null;
            if ("watch_bind".equals(type) && watchId.equals(record.path("watchId").asText())) return record;
        }
        return null;
    }

    synchronized List<JsonNode> replayForRun(String taskRunId, long afterSeq, int limit) {
        var result = new ArrayList<JsonNode>();
        for (var record : records) {
            if (record.path("seq").asLong() <= afterSeq) continue;
            if (!belongsToRun(record, taskRunId)) continue;
            result.add(record);
            if (result.size() >= limit) break;
        }
        return result;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (channel == null) return;
        try {
            channel.close();
        } catch (IOException e) {
            // closing a diagnostic file is best-effort; the session is already finishing
        }
        channel = null;
    }
}
