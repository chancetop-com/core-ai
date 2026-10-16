package ai.core.cli.hub.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves where a hub skill already lives locally, so pull and update operate on the
 * existing directory instead of forking a second copy. A directory pushed from this
 * machine carries the marker written by {@code skill push}; its working copy IS the
 * install. The canonical {@code {ns}/{name}} location still wins when it holds a marker,
 * and is the fallback target when nothing is linked yet.
 * <p>
 * Candidates are found by their marker file, not by parsing SKILL.md: a working copy
 * whose SKILL.md was edited into something unparsable is still identified by its marker
 * and protected from being duplicated or overwritten.
 *
 * @author stephen
 */
public final class SkillInstallResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillInstallResolver.class);

    private final LocalSkillScanner scanner = new LocalSkillScanner();

    /**
     * The local install of a hub skill: the canonical marked directory when present,
     * otherwise any marked directory whose marker belongs to the same hub skill (matching
     * id or qualified name on the same server, so a copy linked to another hub is never
     * mistaken for this one). Null when nothing is linked.
     */
    public LocalSkillScanner.LocalSkill find(Path baseDir, String serverUrl, String id, String qualifiedName) {
        var canonical = canonicalDir(baseDir, qualifiedName);
        if (canonical != null && Files.isDirectory(canonical)) {
            var marker = SkillHubMarker.load(canonical);
            if (marker != null) return installed(canonical, marker, qualifiedName);
        }
        for (var dir : markedDirs(baseDir)) {
            if (dir.equals(canonical)) continue;
            var marker = SkillHubMarker.load(dir);
            if (linked(marker, serverUrl, id, qualifiedName)) return installed(dir, marker, qualifiedName);
        }
        return null;
    }

    /** Install target for a pull: the linked directory when one exists, the canonical location otherwise. */
    public Path resolveTarget(Path baseDir, String serverUrl, String id, String qualifiedName) {
        var linked = find(baseDir, serverUrl, id, qualifiedName);
        if (linked != null) return linked.skillDir();
        var parts = qualifiedName.split("/", 2);
        return baseDir.resolve(parts[0]).resolve(parts[1]);
    }

    private Path canonicalDir(Path baseDir, String qualifiedName) {
        if (qualifiedName == null) return null;
        var parts = qualifiedName.split("/", 2);
        if (parts.length != 2) return null;
        return baseDir.resolve(parts[0]).resolve(parts[1]);
    }

    /** Flat {@code {name}} and namespaced {@code {ns}/{name}} directories that carry a marker. */
    private List<Path> markedDirs(Path baseDir) {
        var dirs = new ArrayList<Path>();
        try (var children = Files.list(baseDir)) {
            for (var child : children.toList()) {
                if (!Files.isDirectory(child)) continue;
                if (SkillHubMarker.load(child) != null) {
                    dirs.add(child);
                    continue;
                }
                collectMarkedChildren(child, dirs);
            }
        } catch (IOException e) {
            LOGGER.debug("failed to scan skill root, dir={}", baseDir, e);
        }
        return dirs;
    }

    private void collectMarkedChildren(Path namespaceDir, List<Path> dirs) {
        try (var children = Files.list(namespaceDir)) {
            for (var child : children.toList()) {
                if (Files.isDirectory(child) && SkillHubMarker.load(child) != null) {
                    dirs.add(child);
                }
            }
        } catch (IOException e) {
            LOGGER.debug("failed to scan skill namespace, dir={}", namespaceDir, e);
        }
    }

    private boolean linked(SkillHubMarker.Marker marker, String serverUrl, String id, String qualifiedName) {
        if (marker == null || !marker.isManaged()) return false;
        if (!sameServer(serverUrl, marker.server())) return false;
        if (id != null && id.equals(marker.id())) return true;
        return qualifiedName != null && qualifiedName.equals(marker.qualifiedName());
    }

    /** An unknown server on either side must not block the link; known URLs ignore the trailing slash. */
    private boolean sameServer(String left, String right) {
        if (left == null || right == null) return true;
        return SkillHubMarker.normalizeServer(left).equals(SkillHubMarker.normalizeServer(right));
    }

    /** A locally edited SKILL.md may no longer parse as a skill; the marker still identifies the install. */
    private LocalSkillScanner.LocalSkill installed(Path dir, SkillHubMarker.Marker marker, String fallbackQualifiedName) {
        var fileName = dir.getFileName();
        var name = fileName == null ? "" : fileName.toString();
        var qualifiedName = marker.qualifiedName() != null ? marker.qualifiedName() : fallbackQualifiedName;
        return new LocalSkillScanner.LocalSkill(name, qualifiedName, dir, null, List.of(), digestOf(dir), marker);
    }

    private String digestOf(Path skillDir) {
        try {
            var skillFile = skillDir.resolve("SKILL.md");
            if (!Files.exists(skillFile)) return null;
            return scanner.digestOf(skillDir, Files.readString(skillFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }
}
