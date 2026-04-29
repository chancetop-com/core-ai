package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import core.framework.json.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class GlobFileToolTest {
    private GlobFileTool tool;
    private ExecutionContext ctx;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        tool = GlobFileTool.builder().build();
        ctx = ExecutionContext.builder()
                .customVariable("workspace", dir.toString())
                .build();
    }

    @Test
    void shouldMatchFilesByExtension() throws IOException {
        touch("Foo.java", "Bar.java", "readme.txt");

        var result = glob(Map.of("pattern", "*.java"));

        assertTrue(result.contains("Foo.java"));
        assertTrue(result.contains("Bar.java"));
    }

    @Test
    void shouldMatchFilesRecursively() throws IOException {
        touch("src/main.js", "src/lib/util.js", "README.md");

        var result = glob(Map.of("pattern", "**/*.js"));

        assertTrue(result.contains("main.js"));
        assertTrue(result.contains("util.js"));
    }

    @Test
    void shouldMatchFilesInSpecificDirectory() throws IOException {
        touch("src/component/Button.tsx", "src/service/api.ts");

        var result = glob(Map.of("pattern", "src/component/*.tsx"));

        assertTrue(result.contains("Button.tsx"));
    }

    @Test
    void shouldSupportBraceExpansion() throws IOException {
        touch("a.tsx", "b.ts", "c.css");

        var result = glob(Map.of("pattern", "*.{tsx,ts}"));

        assertTrue(result.contains("a.tsx") || result.contains("b.ts"));
    }

    @Test
    void shouldReturnNoFilesWhenNoMatch() throws IOException {
        touch("a.txt");

        var result = glob(Map.of("pattern", "*.java"));

        assertTrue(result.contains("No files found"));
    }

    @Test
    void shouldUseWorkspaceFromContextWhenPathOmitted() throws IOException {
        touch("SomeClass.java");

        var result = glob(Map.of("pattern", "*.java"));

        assertTrue(result.contains("SomeClass.java"));
    }

    @Test
    void shouldReportErrorWhenPathIsNotDirectory() throws IOException {
        var file = dir.resolve("file.txt");
        Files.writeString(file, "test");

        var result = glob(Map.of("pattern", "*.txt", "path", file.toString()));

        assertTrue(result.contains("Error") && result.contains("must be a directory"));
    }

    @Test
    void shouldReportErrorWhenDirectoryDoesNotExist() {
        var result = glob(Map.of("pattern", "*.java", "path", "/no/such/dir"));

        assertTrue(result.contains("Error") && result.contains("must be a directory"));
    }

    @Test
    void shouldReportErrorWhenPatternIsEmpty() {
        var result = glob(Map.of("pattern", ""));

        assertTrue(result.contains("Error") && result.contains("required"));
    }

    @Test
    void shouldBuildCorrectly() {
        assertNotNull(tool);
        assertEquals("glob_file", tool.getName());
        assertNotNull(tool.getDescription());
        assertNotNull(tool.getParameters());
    }

    private String glob(Map<String, Object> args) {
        var mutable = new HashMap<>(args);
        mutable.putIfAbsent("path", dir.toString());
        return tool.execute(JSON.toJSON(mutable), ctx).getResult();
    }

    private void touch(String... relativePaths) throws IOException {
        for (var path : relativePaths) {
            var file = dir.resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, path);
        }
    }
}
