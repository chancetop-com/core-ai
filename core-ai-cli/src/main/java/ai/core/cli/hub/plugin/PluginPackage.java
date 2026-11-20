package ai.core.cli.hub.plugin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipInputStream;

/**
 * Reads and extracts a plugin package. A package is a zip with {@code plugin.json} or
 * {@code .claude-plugin/plugin.json} at its root — the same layout the desktop installer accepts.
 * Extraction is limited and path-safe: entries may not escape the staging directory, and the
 * archive may not expand beyond the desktop package limits.
 *
 * @author stephen
 */
public final class PluginPackage {
    private static final String MANIFEST = "plugin.json";
    private static final String LEGACY_MANIFEST = ".claude-plugin/plugin.json";
    private static final int MAX_FILES = 500;
    private static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    /**
     * Extracts {@code archive} into {@code target} and returns the manifest text. The target must
     * be empty or absent; callers stage into a temp directory and publish it afterwards.
     */
    public static InstallResult install(byte[] archive, Path target) throws IOException {
        Files.createDirectories(target);
        String manifest = null;
        int files = 0;
        long total = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            var entry = zip.getNextEntry();
            while (entry != null) {
                if (entry.isDirectory()) {
                    entry = zip.getNextEntry();
                    continue;
                }
                var name = normalizeEntry(entry.getName());
                if (name == null) throw new IOException("unsafe archive entry: " + entry.getName());
                var destination = target.resolve(name).normalize();
                if (!destination.startsWith(target)) throw new IOException("unsafe archive entry: " + entry.getName());
                var parent = destination.getParent();
                if (parent != null) Files.createDirectories(parent);
                long written = copyLimited(zip, destination);
                total += written;
                files += 1;
                if (files > MAX_FILES) throw new IOException("archive has too many files");
                if (total > MAX_TOTAL_BYTES) throw new IOException("archive expands beyond 32 MB");
                if (MANIFEST.equals(name) || LEGACY_MANIFEST.equals(name)) {
                    manifest = Files.readString(destination, StandardCharsets.UTF_8);
                }
                zip.closeEntry();
                entry = zip.getNextEntry();
            }
        }
        if (manifest == null) {
            throw new IOException("plugin.json not found at the archive root");
        }
        return new InstallResult(files, manifest);
    }

    /** Reads only the root manifest, for {@code plugin publish} of a local package. */
    public static String readManifest(byte[] archive) throws IOException {
        String legacy = null;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            var entry = zip.getNextEntry();
            int files = 0;
            while (entry != null) {
                if (!entry.isDirectory()) {
                    var name = normalizeEntry(entry.getName());
                    if (name == null) throw new IOException("unsafe archive entry: " + entry.getName());
                    files += 1;
                    if (files > MAX_FILES) throw new IOException("archive has too many files");
                    if (MANIFEST.equals(name)) return readText(zip);
                    if (LEGACY_MANIFEST.equals(name)) legacy = readText(zip);
                }
                entry = zip.getNextEntry();
            }
        }
        if (legacy == null) throw new IOException("plugin.json not found at the archive root");
        return legacy;
    }

    private static String readText(ZipInputStream zip) throws IOException {
        var data = zip.readAllBytes();
        if (data.length > MAX_FILE_BYTES) {
            throw new IOException("manifest is larger than 10 MB");
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static long copyLimited(ZipInputStream zip, Path destination) throws IOException {
        long written = 0;
        try (OutputStream output = Files.newOutputStream(destination)) {
            var buffer = new byte[8192];
            int read = zip.read(buffer);
            while (read != -1) {
                written += read;
                if (written > MAX_FILE_BYTES) throw new IOException("archive entry is larger than 10 MB");
                output.write(buffer, 0, read);
                read = zip.read(buffer);
            }
        }
        return written;
    }

    /** Rejects absolute paths, traversal, and Windows-only unsafe segments. */
    private static String normalizeEntry(String name) {
        var normalized = name.replace('\\', '/');
        if (normalized.isBlank() || normalized.length() > 512) return null;
        if (normalized.startsWith("/") || normalized.matches("^[a-zA-Z]:.*")) return null;
        var segments = normalized.split("/");
        for (var segment : segments) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) return null;
            if (segment.matches(".*[<>:\"|?*].*")) return null;
            if (segment.matches("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$")) return null;
            if (segment.endsWith(".") || segment.endsWith(" ")) return null;
        }
        return normalized;
    }

    /**
     * Replaces {@code target} with the contents of {@code staging}. The caller owns cleanup of
     * {@code staging} when this throws.
     */
    public static void publish(Path staging, Path target) throws IOException {
        if (Files.exists(target)) {
            deleteRecursively(target);
        }
        var parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            copyRecursively(staging, target);
        }
    }

    public static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            for (var item : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        try (var walk = Files.walk(source)) {
            for (var item : walk.toList()) {
                var destination = target.resolve(source.relativize(item).toString());
                if (Files.isDirectory(item)) {
                    Files.createDirectories(destination);
                } else {
                    var parent = destination.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    Files.copy(item, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private PluginPackage() {
    }

    public record InstallResult(int files, String manifest) {
    }
}
