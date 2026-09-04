package ai.core.cli.hub.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipInputStream;

/**
 * Installs a pulled skill ZIP into a target directory and writes the
 * {@code .skill-hub.json} marker. Refuses to overwrite content it does not own:
 * a directory without a marker, or with a local digest that drifted from the marker,
 * needs {@code force}. Every archive entry is normalized and verified to stay
 * inside the target directory before anything is written (zip-slip guard).
 *
 * @author stephen
 */
public class SkillInstaller {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillInstaller.class);
    private static final String SKILL_FILE_NAME = "SKILL.md";

    private final LocalSkillScanner scanner = new LocalSkillScanner();

    /**
     * @param targetDir target directory the skill lands in (caller applied the flat/namespaced layout)
     * @param zipBytes  archive bytes produced by the hub archive endpoint
     * @param source    identity of the server content (qualified name, id, digest, server); pulled_at is set here
     * @param force     allow overwriting unmanaged or locally modified directories
     * @return outcome; {@code replaced=false, files=0} means the directory was already up to date
     * @throws IllegalStateException on local write failures or an unforced overwrite conflict
     */
    public InstallOutcome install(Path targetDir, byte[] zipBytes, SkillHubMarker.Marker source, boolean force) {
        ExistingState existing = readExistingState(targetDir);
        if (existing != null) {
            OverwriteDecision decision = overwriteDecision(existing, source.digest(), force);
            if (decision == OverwriteDecision.UP_TO_DATE) return new InstallOutcome(targetDir, false, 0);
            if (decision == OverwriteDecision.FORBIDDEN) {
                throw new IllegalStateException(existing.marker() == null
                        ? "target exists without a hub marker, use --force to overwrite: " + targetDir
                        : "skill was modified locally, use --force to overwrite: " + targetDir);
            }
        }
        List<ZipEntryData> entries = readEntries(targetDir, zipBytes, source.qualifiedName());
        try {
            wipeIfExists(targetDir);
            Files.createDirectories(targetDir);
            int files = writeEntries(entries);
            var marker = new SkillHubMarker.Marker(source.qualifiedName(), source.id(), source.digest(),
                    source.server(), ZonedDateTime.now().toInstant().toString());
            SkillHubMarker.write(targetDir, marker);
            return new InstallOutcome(targetDir, true, files);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write skill files to " + targetDir, e);
        }
    }

    /**
     * An existing directory may only be rewritten when it is already up to date, or the
     * caller passed {@code force}; locally modified or unmanaged content is protected.
     */
    private OverwriteDecision overwriteDecision(ExistingState existing, String newDigest, boolean force) {
        if (force) return OverwriteDecision.ALLOWED;
        var marker = existing.marker();
        String localDigest = existing.localDigest();
        if (marker == null || marker.digest() == null || localDigest == null) {
            return marker == null ? OverwriteDecision.FORBIDDEN : OverwriteDecision.ALLOWED;
        }
        if (marker.digest().equals(newDigest) && localDigest.equals(newDigest)) {
            return OverwriteDecision.UP_TO_DATE;
        }
        if (!localDigest.equals(marker.digest())) return OverwriteDecision.FORBIDDEN;
        return OverwriteDecision.ALLOWED;   // outdated: marker matches disk, server content changed
    }

    /** Decodes and validates every entry before anything touches the disk. */
    private List<ZipEntryData> readEntries(Path targetDir, byte[] zipBytes, String qualifiedName) {
        var entries = new ArrayList<ZipEntryData>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory()) continue;
                var target = resolveInside(targetDir, entry.getName());
                if (target == null) {
                    throw new IllegalStateException("archive entry escapes skill directory: " + entry.getName());
                }
                entries.add(new ZipEntryData(target, zip.readAllBytes()));
            }
            return entries;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read skill archive: " + qualifiedName, e);
        }
    }

    private int writeEntries(List<ZipEntryData> entries) throws IOException {
        int files = 0;
        for (var item : entries) {
            var parent = item.target().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(item.target(), item.bytes());
            files++;
        }
        return files;
    }

    /** Normalizes an archive entry path and verifies it stays inside the target directory. */
    private Path resolveInside(Path targetDir, String entryName) {
        var normalized = Path.of(entryName.replace('\\', '/')).normalize();
        var target = targetDir.resolve(normalized).normalize();
        if (target.equals(targetDir) || !target.startsWith(targetDir)) return null;
        return target;
    }

    /** Existing content of a target directory, with the local digest; null when the directory is empty or absent. */
    private ExistingState readExistingState(Path targetDir) {
        if (!Files.isDirectory(targetDir)) return null;
        try (var walk = Files.walk(targetDir)) {
            var files = walk.filter(Files::isRegularFile).toList();
            if (files.isEmpty()) return null;
            var marker = SkillHubMarker.load(targetDir);
            String localDigest = null;
            var skillMd = targetDir.resolve(SKILL_FILE_NAME);
            if (Files.isRegularFile(skillMd)) {
                String content = Files.readString(skillMd, StandardCharsets.UTF_8);
                localDigest = scanner.digestOf(targetDir, content);
            }
            return new ExistingState(marker, localDigest);
        } catch (IOException e) {
            LOGGER.debug("failed to inspect existing skill dir, dir={}", targetDir, e);
            return null;
        }
    }

    private void wipeIfExists(Path targetDir) throws IOException {
        if (!Files.exists(targetDir)) return;
        try (var walk = Files.walk(targetDir)) {
            var paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (var path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private enum OverwriteDecision {
        ALLOWED, FORBIDDEN, UP_TO_DATE
    }

    public record InstallOutcome(Path dir, boolean replaced, int files) {
    }

    private record ZipEntryData(Path target, byte[] bytes) {
    }

    private record ExistingState(SkillHubMarker.Marker marker, String localDigest) {
    }
}
