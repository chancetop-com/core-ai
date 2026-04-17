package ai.core.server.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * SkillStorage backed by a local filesystem (or a mounted PersistentVolume in Kubernetes).
 * Expected layout: {basePath}/{namespace}/{name}/SKILL.md + subdirectories
 *
 * @author xander
 */
public class LocalFileSystemSkillStorage implements SkillStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalFileSystemSkillStorage.class);
    private static final String SKILL_FILE_NAME = "SKILL.md";

    private final Path basePath;

    public LocalFileSystemSkillStorage(String basePath) {
        this.basePath = Path.of(basePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.basePath);
            LOGGER.info("skill storage initialized, basePath={}", this.basePath);
        } catch (IOException e) {
            throw new RuntimeException("failed to create skill storage base dir: " + this.basePath, e);
        }
    }

    @Override
    public Path skillDir(String namespace, String name) {
        var dir = basePath.resolve(namespace).resolve(name).normalize();
        if (!dir.startsWith(basePath)) {
            throw new IllegalArgumentException("skill path escapes base: " + dir);
        }
        return dir;
    }

    @Override
    public boolean exists(String namespace, String name) {
        return Files.isDirectory(skillDir(namespace, name));
    }

    @Override
    public void writeSkillMd(String namespace, String name, String content) {
        var dir = skillDir(namespace, name);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(SKILL_FILE_NAME), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("failed to write SKILL.md: " + dir, e);
        }
    }

    @Override
    public void writeResource(String namespace, String name, String relPath, byte[] bytes) {
        var dir = skillDir(namespace, name);
        var target = dir.resolve(relPath).normalize();
        if (!target.startsWith(dir)) {
            throw new IllegalArgumentException("resource path escapes skill dir: " + relPath);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new RuntimeException("failed to write resource: " + target, e);
        }
    }

    @Override
    public void copyDirectory(String namespace, String name, Path sourceDir) {
        var targetDir = skillDir(namespace, name);
        try {
            if (Files.exists(targetDir)) {
                deleteRecursive(targetDir);
            }
            Files.createDirectories(targetDir);
            try (var walk = Files.walk(sourceDir)) {
                walk.forEach(src -> {
                    var rel = sourceDir.relativize(src);
                    var dest = targetDir.resolve(rel.toString()).normalize();
                    if (!dest.startsWith(targetDir)) return;
                    try {
                        if (Files.isDirectory(src)) {
                            Files.createDirectories(dest);
                        } else {
                            Files.createDirectories(dest.getParent());
                            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("copy failed: " + src + " -> " + dest, e);
                    }
                });
            }
        } catch (IOException e) {
            throw new RuntimeException("failed to copy directory to storage: " + targetDir, e);
        }
    }

    @Override
    public String readSkillMd(String namespace, String name) {
        var file = skillDir(namespace, name).resolve(SKILL_FILE_NAME);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("failed to read SKILL.md: " + file, e);
        }
    }

    @Override
    public byte[] readResource(String namespace, String name, String relPath) {
        var dir = skillDir(namespace, name);
        var target = dir.resolve(relPath).normalize();
        if (!target.startsWith(dir)) {
            throw new IllegalArgumentException("resource path escapes skill dir: " + relPath);
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new RuntimeException("failed to read resource: " + target, e);
        }
    }

    @Override
    public List<String> listResources(String namespace, String name) {
        var dir = skillDir(namespace, name);
        if (!Files.isDirectory(dir)) return List.of();
        var result = new ArrayList<String>();
        try (var walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(f -> {
                var rel = dir.relativize(f).toString().replace('\\', '/');
                if (!SKILL_FILE_NAME.equals(rel)) {
                    result.add(rel);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("failed to list resources: " + dir, e);
        }
        return result;
    }

    @Override
    public void delete(String namespace, String name) {
        var dir = skillDir(namespace, name);
        if (Files.exists(dir)) {
            deleteRecursive(dir);
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void deleteRecursive(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            LOGGER.warn("failed to delete dir: {}", dir, e);
        }
    }
}
