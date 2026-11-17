package ai.core.cli.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginSkillRootsTest {

    @TempDir
    Path temp;

    private Path skillRoot(String digest) throws IOException {
        var dir = temp.resolve(digest).resolve("skills");
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    void parsesRootsInsideThePackageStore() throws IOException {
        var root = skillRoot("abc123");
        var json = "[{\"name\":\"example.hello\",\"dir\":\"" + root.toString().replace("\\", "\\\\") + "\"}]";

        var roots = PluginSkillRoots.parse(json, temp);

        assertEquals(1, roots.size());
        assertEquals("example.hello", roots.get(0).name());
        assertEquals(root.toRealPath().toString(), roots.get(0).dir());
    }

    @Test
    void rejectsRootsOutsideThePackageStore() throws IOException {
        var outside = Files.createDirectories(temp.getParent().resolve("plugin-skills-outside"));
        var json = "[{\"name\":\"outside\",\"dir\":\"" + outside.toString().replace("\\", "\\\\") + "\"}]";

        assertTrue(PluginSkillRoots.parse(json, temp).isEmpty());
    }

    @Test
    void rejectsTraversalAndTheStoreRootItself() throws IOException {
        var escaped = temp.resolve("abc123").resolve("..").resolve("..").resolve("escaped-skills");
        Files.createDirectories(escaped);
        var traversalJson = "[{\"name\":\"traversal\",\"dir\":\"" + escaped.toString().replace("\\", "\\\\") + "\"}]";
        var rootJson = "[{\"name\":\"root\",\"dir\":\"" + temp.toString().replace("\\", "\\\\") + "\"}]";

        assertTrue(PluginSkillRoots.parse(traversalJson, temp).isEmpty());
        assertTrue(PluginSkillRoots.parse(rootJson, temp).isEmpty());
        assertTrue(Files.isDirectory(escaped));
    }

    @Test
    void rejectsInvalidNamesMissingDirectoriesAndMalformedJson() throws IOException {
        var root = skillRoot("abc123");
        var escaped = root.toString().replace("\\", "\\\\");
        assertTrue(PluginSkillRoots.parse("[{\"name\":\"bad name\",\"dir\":\"" + escaped + "\"}]", temp).isEmpty());
        assertTrue(PluginSkillRoots.parse("[{\"name\":\"missing\",\"dir\":\"" + temp.resolve("nope").toString().replace("\\", "\\\\") + "\"}]", temp).isEmpty());
        assertTrue(PluginSkillRoots.parse("{not json", temp).isEmpty());
        assertTrue(PluginSkillRoots.parse("[]", temp).isEmpty());
        assertTrue(PluginSkillRoots.parse(null, temp).isEmpty());
    }

    @Test
    void deduplicatesByResolvedDirectory() throws IOException {
        var root = skillRoot("abc123");
        var escaped = root.toString().replace("\\", "\\\\");
        var json = "[{\"name\":\"one\",\"dir\":\"" + escaped + "\"},{\"name\":\"two\",\"dir\":\"" + escaped + "\"}]";

        var roots = PluginSkillRoots.parse(json, temp);

        assertEquals(1, roots.size());
        assertEquals("one", roots.get(0).name());
    }
}
