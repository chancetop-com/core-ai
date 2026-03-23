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
        var file = Path.of(skill.getSkillDir(), resourcePath);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SkillLoadException("failed to read resource: " + file, e);
        }
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
