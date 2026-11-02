package ai.core.session.transcript;

import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * File layer for one session transcript: append-only JSONL, blob sidecar files and the in-memory
 * index used by resume and by {@code session/records}.
 *
 * @author stephen
 */
@SuppressWarnings("PMD.AvoidSynchronizedAtMethodLevel")
public final class TranscriptStore implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(TranscriptStore.class);
    private static final String FILE_SUFFIX = ".events.jsonl";
    private static final String BLOB_SUFFIX = ".blobs";

    public static TranscriptStore open(Path dir, String sessionId) {
        try {
            Files.createDirectories(dir);
            var store = new TranscriptStore(dir, sessionId);
            store.load();
            store.channel = FileChannel.open(store.file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            return store;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open transcript: " + sessionId, e);
        }
    }

    public static void delete(Path dir, String sessionId) {
        try {
            Files.deleteIfExists(dir.resolve(sessionId + FILE_SUFFIX));
            deleteRecursively(dir.resolve(sessionId + BLOB_SUFFIX));
        } catch (IOException e) {
            LOGGER.warn("failed to delete transcript, sessionId={}", sessionId, e);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            for (var child : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(child);
            }
        }
    }

    private final Path dir;
    private final String sessionId;
    private final Path file;
    private final Path blobs;
    private final List<TranscriptRecord> records = new ArrayList<>();
    private final List<TranscriptRecord> baseRecords = new ArrayList<>();
    private FileChannel channel;
    private long maxSeq;
    private int badLines;
    private boolean closed;

    private TranscriptStore(Path dir, String sessionId) {
        this.dir = dir;
        this.sessionId = sessionId;
        this.file = dir.resolve(sessionId + FILE_SUFFIX);
        this.blobs = dir.resolve(sessionId + BLOB_SUFFIX);
    }

    private void load() throws IOException {
        if (!Files.exists(file)) return;
        truncatePartialTail();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            var line = reader.readLine();
            while (line != null) {
                loadLine(line);
                line = reader.readLine();
            }
        }
    }

    /**
     * A crash between the two halves of a line leaves a partial tail. Drop it before the next append,
     * otherwise the appended record would concatenate to the fragment and both lines would be lost.
     */
    private void truncatePartialTail() throws IOException {
        var size = Files.size(file);
        if (size == 0) return;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var last = ByteBuffer.allocate(1);
            channel.read(last, size - 1);
            if (last.get(0) == '\n') return;
            long position = size;
            long newlineAt = -1;
            var chunk = ByteBuffer.allocate(8192);
            while (position > 0 && newlineAt < 0) {
                int length = (int) Math.min(chunk.capacity(), position);
                long start = position - length;
                chunk.clear();
                chunk.limit(length);
                channel.read(chunk, start);
                for (int i = length - 1; i >= 0; i--) {
                    if (chunk.get(i) == '\n') {
                        newlineAt = start + i;
                        break;
                    }
                }
                position = start;
            }
            channel.truncate(newlineAt + 1);
            LOGGER.warn("dropped a truncated transcript tail, sessionId={}, bytes={}", sessionId, size - newlineAt - 1);
        }
    }

    private void loadLine(String line) {
        if (line.isBlank()) return;
        try {
            var record = TranscriptRecord.fromMap(JsonUtil.fromJson(new TypeReference<>() { }, line));
            records.add(record);
            if (record.seq > maxSeq) maxSeq = record.seq;
        } catch (RuntimeException e) {
            badLines++;
            LOGGER.warn("skipping bad transcript line, sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    public synchronized long append(TranscriptRecord record) {
        if (closed) throw new IllegalStateException("transcript store is closed: " + sessionId);
        var line = JsonUtil.toJson(record.toMap()) + "\n";
        var bytes = StandardCharsets.UTF_8.encode(line);
        writeFully(bytes);
        records.add(record);
        if (record.seq > maxSeq) maxSeq = record.seq;
        return record.seq;
    }

    /**
     * Cancelling a turn interrupts the thread that runs it. When that interrupt lands while this
     * thread is writing, the JDK closes the channel and {@code write} fails with
     * {@link ClosedByInterruptException} — and the store would stay broken for the rest of the
     * session, because every later append hits a closed channel. Reopen and finish the line instead,
     * and keep the interrupt pending for the code that asked for it.
     */
    private void writeFully(ByteBuffer bytes) {
        boolean wasInterrupted = Thread.interrupted();
        boolean interrupted = false;
        try {
            interrupted = writeRemaining(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to append transcript: " + sessionId, e);
        } finally {
            if (wasInterrupted || interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean writeRemaining(ByteBuffer bytes) throws IOException {
        boolean interrupted = false;
        while (bytes.hasRemaining()) {
            try {
                channel.write(bytes);
            } catch (ClosedByInterruptException e) {
                interrupted = true;
                // a pending interrupt fails every write on an interruptible channel, so the retry
                // needs a clean flag; writeFully puts it back for the cancelling side
                Thread.interrupted();
                reopen();
            } catch (ClosedChannelException ignored) {
                reopen();
            }
        }
        return interrupted;
    }

    private void reopen() {
        if (closed) throw new IllegalStateException("transcript store is closed: " + sessionId);
        try {
            channel.close();
        } catch (IOException ignored) {
            // an interrupted channel is already closed
        }
        try {
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
            LOGGER.warn("reopened transcript after an interrupted write, sessionId={}", sessionId);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to reopen transcript: " + sessionId, e);
        }
    }

    public List<TranscriptRecord> records() {
        synchronized (this) {
            return List.copyOf(records);
        }
    }

    /** Base projection records (negative seqs) followed by file records, all ordered by seq. */
    public List<TranscriptRecord> allRecords() {
        synchronized (this) {
            var all = new ArrayList<TranscriptRecord>(baseRecords.size() + records.size());
            all.addAll(baseRecords);
            all.addAll(records);
            return all;
        }
    }

    public synchronized void setBaseRecords(List<TranscriptRecord> base) {
        baseRecords.clear();
        if (base != null) baseRecords.addAll(base);
    }

    public List<TranscriptRecord> baseRecords() {
        synchronized (this) {
            return List.copyOf(baseRecords);
        }
    }

    public Optional<TranscriptRecord> find(long seq) {
        for (var record : allRecords()) {
            if (record.seq == seq) return Optional.of(record);
        }
        return Optional.empty();
    }

    public long maxSeq() {
        return maxSeq;
    }

    public int badLines() {
        return badLines;
    }

    public long fileSize() {
        try {
            return Files.exists(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    public Path dir() {
        return dir;
    }

    public String sessionId() {
        return sessionId;
    }

    public Path file() {
        return file;
    }

    public Path blobsDir() {
        return blobs;
    }

    public String writeBlob(long seq, String field, int index, String text) {
        try {
            Files.createDirectories(blobs);
            var name = seq + "." + field + "." + index;
            Files.writeString(blobs.resolve(name), text, StandardCharsets.UTF_8);
            return name;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write transcript blob: " + sessionId, e);
        }
    }

    public Path blobPath(String ref) {
        if (ref == null || ref.isBlank()) return null;
        var path = blobs.resolve(ref).normalize();
        return path.startsWith(blobs.normalize()) ? path : null;
    }

    public String readBlob(String ref) {
        var path = blobPath(ref);
        if (path == null || !Files.isRegularFile(path)) return null;
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (channel == null) return;
        try {
            channel.close();
        } catch (IOException e) {
            LOGGER.warn("failed to close transcript file, sessionId={}", sessionId, e);
        }
    }


}
