package ai.core.skill;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * @author stephen
 */
public class FilesystemSkillProvider implements SkillProvider {
    private final String name;
    private final String path;
    private final int priority;
    private final int maxFileSize;

    private List<SkillMetadata> cached;

    public FilesystemSkillProvider(String name, String path, int priority, int maxFileSize) {
        this.name = name;
        this.path = path;
        this.priority = priority;
        this.maxFileSize = maxFileSize;
    }

    public FilesystemSkillProvider(String name, String path, int priority) {
        this(name, path, priority, 10 * 1024 * 1024);
    }

    @Override
    public List<SkillMetadata> listSkills() {
        if (cached == null) {
            var loader = new SkillLoader(maxFileSize);
            cached = loader.loadFromSource(path);
        }
        return cached;
    }

    @Override
    public String readContent(SkillMetadata skill) {
        if (skill.getPath() == null) {
            throw new SkillLoadException("skill has no filesystem path: " + skill.getName());
        }
        try {
            byte[] bytes = Files.readAllBytes(Path.of(skill.getPath()));
            if (bytes.length > maxFileSize) {
                throw new SkillLoadException("skill file exceeds max size (" + maxFileSize + "): " + skill.getPath());
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SkillLoadException("failed to read skill file: " + skill.getPath(), e);
        }
    }

    @Override
    public String readResource(SkillMetadata skill, String resourcePath) {
        if (skill.getSkillDir() == null) {
            throw new SkillLoadException("skill has no directory: " + skill.getName());
        }
        var direct = Path.of(skill.getSkillDir(), resourcePath);
        var content = readFile(direct);
        if (content != null) return content;
        var shared = readFromSharedRoot(skill.getSkillDir(), resourcePath);
        if (shared != null) return shared;
        throw new SkillLoadException("failed to read resource: " + direct);
    }

    private String readFile(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SkillLoadException("failed to read resource: " + file, e);
        }
    }

    /**
     * A skill inside a multi-skill tree may reference a file by its shared-root path (for example
     * {@code ads/references/x.md} from a skill below a {@code skills/} root). Look for that path on each
     * ancestor of the skill directory, bounded by the provider root so a skill cannot read outside the
     * tree it was discovered in.
     */
    private String readFromSharedRoot(String skillDir, String resourcePath) {
        if (path == null) return null;
        Path root;
        Path dir;
        try {
            root = Path.of(path).toAbsolutePath().normalize();
            dir = Path.of(skillDir).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
        if (!dir.startsWith(root)) return null;
        for (var ancestor = dir.getParent(); ancestor != null && ancestor.startsWith(root); ancestor = ancestor.getParent()) {
            var candidate = ancestor.resolve(resourcePath).normalize();
            if (!candidate.startsWith(root)) continue;
            var content = readFile(candidate);
            if (content != null) return content;
        }
        return null;
    }

    @Override
    public int priority() {
        return priority;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }
}
