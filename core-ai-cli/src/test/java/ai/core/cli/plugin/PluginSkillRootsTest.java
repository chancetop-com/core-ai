package ai.core.cli.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginSkillRootsTest {

    private static final String DIGEST = "a".repeat(64);

    @TempDir
    Path temp;

    private Path skillRoot(String digest) throws IOException {
        var dir = temp.resolve(digest).resolve("skills");
        Files.createDirectories(dir);
        return dir;
    }

    private String entry(String name, Path dir) {
        return "{\"name\":\"" + name + "\",\"dir\":\"" + dir.toString().replace("\\", "\\\\")
                + "\",\"digest\":\"" + DIGEST + "\",\"grantEpoch\":1}";
    }

    @Test
    void parsesRootsInsideThePackageStore() throws IOException {
        var root = skillRoot("abc123");

        var roots = PluginSkillRoots.parse("[" + entry("example.hello", root) + "]", temp);

        assertEquals(1, roots.size());
        assertEquals("example.hello", roots.get(0).name());
        assertEquals(DIGEST, roots.get(0).digest());
        assertEquals(1, roots.get(0).grantEpoch());
        assertEquals(root.toRealPath().toString(), roots.get(0).dir());
    }

    @Test
    void rejectsRootsOutsideThePackageStore() throws IOException {
        var outside = Files.createDirectories(temp.getParent().resolve("plugin-skills-outside"));

        assertTrue(PluginSkillRoots.parse("[" + entry("outside", outside) + "]", temp).isEmpty());
    }

    @Test
    void rejectsTraversalAndTheStoreRootItself() throws IOException {
        var escaped = temp.resolve("abc123").resolve("..").resolve("..").resolve("escaped-skills");
        Files.createDirectories(escaped);
        var rootJson = "{\"name\":\"root\",\"dir\":\"" + temp.toString().replace("\\", "\\\\")
                + "\",\"digest\":\"" + DIGEST + "\",\"grantEpoch\":1}";

        assertTrue(PluginSkillRoots.parse("[" + entry("traversal", escaped) + "]", temp).isEmpty());
        assertTrue(PluginSkillRoots.parse("[" + rootJson + "]", temp).isEmpty());
    }

    @Test
    void rejectsInvalidNamesMissingDirectoriesAndMalformedJson() throws IOException {
        var root = skillRoot("abc123");
        assertTrue(PluginSkillRoots.parse("[{\"name\":\"bad name\",\"dir\":\"" + root.toString().replace("\\", "\\\\")
                + "\",\"digest\":\"" + DIGEST + "\",\"grantEpoch\":1}]", temp).isEmpty());
        assertTrue(PluginSkillRoots.parse("[" + entry("missing", temp.resolve("nope")) + "]", temp).isEmpty());
        assertTrue(PluginSkillRoots.parse("{not json", temp).isEmpty());
        assertTrue(PluginSkillRoots.parse("[]", temp).isEmpty());
        assertTrue(PluginSkillRoots.parse(null, temp).isEmpty());
    }

    @Test
    void rejectsMissingDigestAndNegativeEpoch() throws IOException {
        var root = skillRoot("abc123");
        var escaped = root.toString().replace("\\", "\\\\");
        var noDigest = "{\"name\":\"x\",\"dir\":\"" + escaped + "\",\"grantEpoch\":1}";
        var negativeEpoch = "{\"name\":\"x\",\"dir\":\"" + escaped + "\",\"digest\":\"" + DIGEST + "\",\"grantEpoch\":-1}";

        assertTrue(PluginSkillRoots.parse("[" + noDigest + "]", temp).isEmpty());
        assertTrue(PluginSkillRoots.parse("[" + negativeEpoch + "]", temp).isEmpty());
    }

    @Test
    void deduplicatesByResolvedDirectory() throws IOException {
        var root = skillRoot("abc123");
        var json = "[" + entry("one", root) + "," + entry("two", root) + "]";

        var roots = PluginSkillRoots.parse(json, temp);

        assertEquals(1, roots.size());
        assertEquals("one", roots.get(0).name());
    }
}
