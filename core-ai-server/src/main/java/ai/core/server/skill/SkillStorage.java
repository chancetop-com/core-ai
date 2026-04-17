package ai.core.server.skill;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Pluggable storage backend for skill files.
 * Mongo holds metadata; this interface holds the actual SKILL.md and resource files.
 *
 * @author xander
 */
public interface SkillStorage {

    /** Absolute path to the skill directory. Does not guarantee existence. */
    Path skillDir(String namespace, String name);

    boolean exists(String namespace, String name);

    /** Write SKILL.md body as UTF-8 text. Creates dirs as needed. */
    void writeSkillMd(String namespace, String name, String content);

    /** Write an arbitrary file under the skill directory. relPath is relative, must not escape. */
    void writeResource(String namespace, String name, String relPath, byte[] bytes);

    /** Copy an entire directory tree into the skill storage location (used for REPO source). */
    void copyDirectory(String namespace, String name, Path sourceDir);

    String readSkillMd(String namespace, String name);

    byte[] readResource(String namespace, String name, String relPath);

    /** List resource files (relative paths) under the skill directory, excluding SKILL.md. */
    List<String> listResources(String namespace, String name);

    /** Write multiple resources at once. Does not clear existing files. */
    default void writeResources(String namespace, String name, Map<String, byte[]> resources) {
        if (resources == null) return;
        for (var entry : resources.entrySet()) {
            writeResource(namespace, name, entry.getKey(), entry.getValue());
        }
    }

    /** Remove the entire skill directory. */
    void delete(String namespace, String name);
}
