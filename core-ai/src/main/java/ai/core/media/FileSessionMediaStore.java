package ai.core.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * File-backed media store for the CLI / Desktop. Every session owns one directory; bytes are written
 * once per content hash with an atomic rename, so a crash never leaves a half image behind.
 *
 * @author core-ai
 */
public class FileSessionMediaStore implements SessionMediaStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileSessionMediaStore.class);
    private static final String MEDIA_DIR = "media";
    private static final String DEFAULT_EXTENSION = "bin";

    private static void writeAtomically(Path target, byte[] data) {
        var temp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        writeTemp(temp, data, target);
        moveIntoPlace(temp, target);
    }

    private static void writeTemp(Path temp, byte[] data, Path target) {
        try {
            Files.write(temp, data);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new UncheckedIOException("failed to write session media object: " + target, e);
        }
    }

    private static void moveIntoPlace(Path temp, Path target) {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            moveWithoutAtomic(temp, target);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new UncheckedIOException("failed to move session media object: " + target, e);
        }
    }

    private static void moveWithoutAtomic(Path temp, Path target) {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new UncheckedIOException("failed to move session media object: " + target, e);
        }
    }

    private static long deleteObject(Path path) {
        try {
            if (!Files.isRegularFile(path)) return 0;
            var size = Files.size(path);
            return Files.deleteIfExists(path) ? size : 0;
        } catch (IOException e) {
            LOGGER.debug("failed to delete session media object: {}", path, e);
            return 0;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.debug("failed to delete temp media file: {}", path, e);
        }
    }

    private static String extension(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) return DEFAULT_EXTENSION;
        var normalized = mediaType.toLowerCase(Locale.ROOT);
        if ("image/jpeg".equals(normalized) || "jpg".equals(normalized)) return "jpg";
        var slash = normalized.indexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    private static String fileName(Path path) {
        var name = path.getFileName();
        return name == null ? "" : name.toString();
    }

    private static String extensionOf(Path path) {
        var name = fileName(path);
        var dot = name.lastIndexOf('.');
        return dot < 0 ? DEFAULT_EXTENSION : name.substring(dot + 1);
    }

    private static String guessMediaType(String fileName) {
        var dot = fileName.lastIndexOf('.');
        var ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            default -> "application/octet-stream";
        };
    }

    private final Path sessionDirectory;
    private final Path mediaDirectory;
    private final Map<String, Path> objects = new ConcurrentHashMap<>();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong missed = new AtomicLong();
    private final AtomicLong evicted = new AtomicLong();

    public FileSessionMediaStore(Path sessionDirectory) {
        this.sessionDirectory = sessionDirectory.toAbsolutePath().normalize();
        this.mediaDirectory = this.sessionDirectory.resolve(MEDIA_DIR);
        try {
            Files.createDirectories(mediaDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create session media directory: " + mediaDirectory, e);
        }
        scan();
    }

    public Path sessionDirectory() {
        return sessionDirectory;
    }

    @Override
    public MediaRef put(SessionMediaKey key, byte[] data, String mediaType, String displayName, String source) {
        if (data == null || data.length == 0) throw new IllegalArgumentException("media bytes are required");
        if (data.length > MediaStoreLimits.MAX_IMAGE_BYTES) {
            throw new MediaLimitExceededException("image exceeds the per-object budget", MediaStoreLimits.MAX_IMAGE_BYTES, data.length);
        }
        var sha256 = MediaHash.sha256(data);
        var existing = objects.get(sha256);
        if (existing != null && Files.exists(existing)) {
            return fileRef(sha256, existing, displayName, source, mediaType);
        }
        var current = bytes.get();
        if (current + data.length > MediaStoreLimits.MAX_SESSION_BYTES) {
            throw new MediaLimitExceededException("session media store exceeds its budget", MediaStoreLimits.MAX_SESSION_BYTES, current + data.length);
        }
        var target = mediaDirectory.resolve(sha256 + "." + extension(mediaType));
        writeAtomically(target, data);
        objects.put(sha256, target);
        bytes.addAndGet(data.length);
        return fileRef(sha256, target, displayName, source, mediaType);
    }

    @Override
    public MediaRef register(SessionMediaKey key, MediaRef existing) {
        if (existing == null) throw new IllegalArgumentException("media ref is required");
        existing.validate();
        if (existing.kind == MediaRefKind.FILE) {
            requireInsideRoot(existing.path);
        }
        return existing;
    }

    @Override
    public Optional<MediaRef> find(SessionMediaKey key, String mediaId) {
        if (mediaId == null) return Optional.empty();
        var path = objects.get(mediaId);
        if (path == null || !Files.exists(path)) return Optional.empty();
        return Optional.of(fileRef(mediaId, path, null, null, null));
    }

    @Override
    public Optional<byte[]> read(SessionMediaKey key, MediaRef ref) {
        if (ref == null) return Optional.empty();
        if (ref.kind == MediaRefKind.FILE) {
            return readFile(ref);
        }
        if (ref.kind == MediaRefKind.URL) {
            // remote bytes are fetched by the provider, never by the local store
            return Optional.empty();
        }
        missed.incrementAndGet();
        return Optional.empty();
    }

    @Override
    public void deleteSession(String sessionId) {
        var removed = 0L;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mediaDirectory)) {
            for (var path : stream) {
                removed += deleteObject(path);
            }
            objects.clear();
            bytes.addAndGet(-removed);
            evicted.addAndGet(removed);
        } catch (IOException e) {
            LOGGER.warn("failed to delete session media directory: {}", mediaDirectory, e);
        }
    }

    @Override
    public MediaStoreStats stats() {
        return new MediaStoreStats(bytes.get(), objects.size(), missed.get(), evicted.get());
    }

    private void scan() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mediaDirectory)) {
            for (var path : stream) {
                if (!Files.isRegularFile(path)) continue;
                var fileName = fileName(path);
                var dot = fileName.indexOf('.');
                var id = dot < 0 ? fileName : fileName.substring(0, dot);
                objects.put(id, path);
                bytes.addAndGet(Files.size(path));
            }
        } catch (IOException e) {
            LOGGER.warn("failed to scan session media directory, starting empty: {}", mediaDirectory, e);
        }
    }

    private Optional<byte[]> readFile(MediaRef ref) {
        try {
            var path = requireInsideRoot(ref.path);
            if (!Files.isRegularFile(path) || Files.size(path) > MediaStoreLimits.MAX_IMAGE_BYTES) {
                missed.incrementAndGet();
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(path));
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.warn("failed to read session media: path={}, error={}", ref.path, e.getMessage());
            missed.incrementAndGet();
            return Optional.empty();
        }
    }

    private MediaRef fileRef(String id, Path path, String displayName, String source, String mediaType) {
        var mediaTypeValue = mediaType != null ? mediaType : guessMediaType(fileName(path));
        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            size = 0;
        }
        return MediaRef.file(id, path.toAbsolutePath().toString(), mediaTypeValue, extensionOf(path), size, id)
                .displayName(displayName)
                .source(source);
    }

    private Path requireInsideRoot(String value) {
        if (value == null) throw new IllegalArgumentException("media path is required");
        var path = Path.of(value).toAbsolutePath().normalize();
        if (!path.startsWith(mediaDirectory)) {
            throw new IllegalArgumentException("media path is outside the session media directory");
        }
        return path;
    }
}
