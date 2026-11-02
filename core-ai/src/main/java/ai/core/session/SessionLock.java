package ai.core.session;

import ai.core.utils.JsonUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-process session ownership. The lock is advisory: it prevents two processes from silently
 * overwriting one conversation, while atomic file replacement still guarantees the on-disk file is
 * never torn.
 *
 * @author stephen
 */
public final class SessionLock implements AutoCloseable {
    private static final Map<Path, Holder> LOCAL_HOLDERS = new ConcurrentHashMap<>();

    public sealed interface AcquireResult {
        record Acquired(SessionLock lock) implements AcquireResult {
        }

        record Busy(Holder holder, boolean local) implements AcquireResult {
        }
    }

    public record Holder(Long pid, String process, Long since) {
        public String describe() {
            var who = process == null || process.isBlank() ? "another process" : process;
            var id = pid == null ? "" : " (pid " + pid + ")";
            var when = since == null ? "" : " since " + java.time.Instant.ofEpochMilli(since);
            return who + id + when;
        }
    }

    public static AcquireResult tryAcquire(Path dir, String id, String process) {
        SessionFileNames.check(id);
        var path = dir.resolve(id + ".lock").toAbsolutePath().normalize();
        FileChannel channel = null;
        try {
            Files.createDirectories(dir);
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            var attempt = tryLock(channel);
            if (attempt.lock() == null) {
                var observedHolder = readHolder(channel, path);
                closeQuietly(channel);
                return new AcquireResult.Busy(observedHolder, attempt.local());
            }
            var lock = attempt.lock();
            var holder = new Holder(ProcessHandle.current().pid(), process, System.currentTimeMillis());
            writeHolder(channel, holder);
            LOCAL_HOLDERS.put(path, holder);
            return new AcquireResult.Acquired(new SessionLock(path, channel, lock, holder));
        } catch (IOException e) {
            closeQuietly(channel);
            throw new UncheckedIOException("failed to acquire session lock: " + path, e);
        }
    }

    /**
     * Liveness check for cleanup paths. A stale lock file (process gone) is not held.
     */
    public static boolean isHeld(Path dir, String id) {
        var path = dir.resolve(id + ".lock").toAbsolutePath().normalize();
        if (LOCAL_HOLDERS.containsKey(path)) return true;
        if (!Files.exists(path)) return false;
        try (var channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var lock = channel.tryLock();
            if (lock == null) return true;
            lock.release();
            return false;
        } catch (OverlappingFileLockException | IOException e) {
            // sharing violation / permission: treat as held, cleanup is best-effort
            return true;
        }
    }

    private static Holder readHolder(Path path) {
        var local = LOCAL_HOLDERS.get(path);
        if (local != null) return local;
        try {
            if (!Files.exists(path)) return new Holder(null, "unknown", null);
            return parseHolder(Files.readString(path));
        } catch (RuntimeException | IOException e) {
            return new Holder(null, "unknown", null);
        }
    }

    private static Holder readHolder(FileChannel channel, Path path) {
        var local = LOCAL_HOLDERS.get(path);
        if (local != null) return local;
        try {
            var size = channel.size();
            if (size > 0 && size < 4096) {
                var buffer = ByteBuffer.allocate((int) size);
                channel.position(0);
                while (buffer.hasRemaining()) {
                    // read through the already-open channel: a second open can hit a sharing violation
                    if (channel.read(buffer) <= 0) break;
                }
                return parseHolder(new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // fall back to a path read; the lock is advisory and this is only a diagnostic
        }
        return readHolder(path);
    }

    private static Holder parseHolder(String content) {
        if (content == null || content.isBlank()) return new Holder(null, "unknown", null);
        try {
            var map = JsonUtil.toMap(content.strip());
            var pid = map.get("pid") instanceof Number number ? number.longValue() : null;
            var process = map.get("process") instanceof String text ? text : "unknown";
            var since = map.get("since") instanceof Number number ? number.longValue() : null;
            return new Holder(pid, process, since);
        } catch (RuntimeException e) {
            return new Holder(null, "unknown", null);
        }
    }

    private static void writeHolder(FileChannel channel, Holder holder) throws IOException {
        var bytes = JsonUtil.toJson(Map.of(
                "pid", holder.pid(),
                "process", holder.process(),
                "since", holder.since())).getBytes(StandardCharsets.UTF_8);
        channel.truncate(0);
        channel.position(0);
        var buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) return;
        try {
            channel.close();
        } catch (IOException ignored) {
            // best effort; the channel is already unusable
        }
    }

    private static LockAttempt tryLock(FileChannel channel) throws IOException {
        try {
            var lock = channel.tryLock();
            return new LockAttempt(lock, lock == null);
        } catch (OverlappingFileLockException e) {
            return new LockAttempt(null, true);
        }
    }

    private final Path path;
    private final FileChannel channel;
    private final FileLock lock;
    private final Holder holder;

    private SessionLock(Path path, FileChannel channel, FileLock lock, Holder holder) {
        this.path = path;
        this.channel = channel;
        this.lock = lock;
        this.holder = holder;
    }

    public Holder holder() {
        return holder;
    }

    @Override
    public void close() {
        LOCAL_HOLDERS.remove(path, holder);
        try {
            lock.release();
        } catch (IOException ignored) {
            // best effort; the channel close below releases the OS lock as well
        }
        closeQuietly(channel);
    }

    private record LockAttempt(FileLock lock, boolean local) {
    }
}
