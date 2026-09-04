package ai.core.cli.hub.skill;

import ai.core.skill.SkillLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Scans a skills root (flat {@code {root}/{name}} and namespaced
 * {@code {root}/{namespace}/{name}} layouts) using {@link SkillLoader}, the same
 * parser the agent stack trusts. For every installable skill it reports the real
 * frontmatter name/description and the local content digest — the value compared
 * against the {@code .skill-hub.json} marker and the server digest.
 *
 * @author stephen
 */
public class LocalSkillScanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalSkillScanner.class);
    private static final int MAX_SKILL_FILE_SIZE = 10 * 1024 * 1024;
    private static final String SKILL_FILE_NAME = "SKILL.md";

    public List<LocalSkill> scan(Path root) {
        if (root == null || !Files.isDirectory(root)) return List.of();
        var loader = new SkillLoader(MAX_SKILL_FILE_SIZE);
        var skills = new ArrayList<LocalSkill>();
        for (var metadata : loader.loadFromSource(root.toString())) {
            var skillDir = metadata.getSkillDir();
            if (skillDir == null) continue;
            var dir = Path.of(skillDir);
            try {
                String content = Files.readString(dir.resolve(SKILL_FILE_NAME), StandardCharsets.UTF_8);
                var digest = digestOf(dir, content);
                skills.add(new LocalSkill(metadata.getName(), metadata.getQualifiedName(), dir,
                        metadata.getDescription(), metadata.getResources(), digest, SkillHubMarker.load(dir)));
            } catch (IOException e) {
                LOGGER.debug("failed to read local skill, dir={}", dir, e);
            }
        }
        return skills;
    }

    /** Digest of an installed skill directory (SKILL.md plus every non-dotfile resource). */
    public String digestOf(Path skillDir, String content) throws IOException {
        var resources = new ArrayList<SkillDigest.Resource>();
        for (String path : resourcePaths(skillDir)) {
            String resourceContent = Files.readString(skillDir.resolve(path), StandardCharsets.UTF_8);
            resources.add(new SkillDigest.Resource(path, resourceContent));
        }
        return SkillDigest.of(content, resources);
    }

    /** Recursive resource list matching {@code SkillLoader.scanResources}: sorted, dot-prefixed entries ignored. */
    private List<String> resourcePaths(Path skillDir) throws IOException {
        var paths = new ArrayList<String>();
        try (var walk = Files.walk(skillDir)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                var relative = skillDir.relativize(file).toString().replace('\\', '/');
                if (relative.isEmpty() || SKILL_FILE_NAME.equals(relative)) return;
                if (relative.startsWith(".") || relative.contains("/.")) return;
                paths.add(relative);
            });
        }
        Collections.sort(paths);
        return paths;
    }

    public record LocalSkill(String name, String qualifiedName, Path skillDir, String description,
                             List<String> resources, String digest, SkillHubMarker.Marker marker) {
    }
}
