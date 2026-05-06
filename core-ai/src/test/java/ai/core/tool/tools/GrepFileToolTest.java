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
class GrepFileToolTest {
    private GrepFileTool tool;
    private ExecutionContext ctx;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        tool = GrepFileTool.builder().build();
        ctx = ExecutionContext.builder()
                .customVariable("workspace", dir.toString())
                .build();
    }

    @Test
    void shouldFindMatchesInFiles() throws IOException {
        write("a.txt", "hello world\nfoo bar");
        write("b.txt", "no match here");

        var result = grep(Map.of("pattern", "hello world"));

        assertTrue(result.contains("a.txt"));
        assertTrue(result.contains("hello world"));
    }

    @Test
    void shouldFilterByIncludePattern() throws IOException {
        write("Foo.java", "class Foo");
        write("Bar.java", "class Bar");
        write("readme.md", "class in md");

        var result = grep(Map.of("pattern", "class", "include", "*.java"));

        assertTrue(result.contains("Foo.java"));
        assertTrue(result.contains("Bar.java"));
    }

    @Test
    void shouldReturnNoFilesWhenNoMatch() throws IOException {
        write("a.txt", "nothing here");

        var result = grep(Map.of("pattern", "nonexistent"));

        assertTrue(result.contains("No files found"));
    }


    @Test
    void shouldUseWorkspaceFromContextWhenPathOmitted() throws IOException {
        write("code.java", "public interface GrepFileTool");

        var result = grep(Map.of("pattern", "GrepFileTool"));

        assertTrue(result.contains("code.java"));
    }

    @Test
    void shouldSearchInSpecificFile() throws IOException {
        write("src/Main.java", "public class Main {\n    private int count;\n}");
        write("src/Util.java", "public class Util {\n    private int count;\n}");

        var filePath = dir.resolve("src/Main.java").toString();
        var result = grepFile(Map.of("pattern", "class Main", "path", filePath));

        assertTrue(result.contains("Main.java"));
    }

    @Test
    void shouldSearchInSpecificFileNoMatch() throws IOException {
        write("src/Main.java", "public class Main {\n    private int count;\n}");

        var filePath = dir.resolve("src/Main.java").toString();
        var result = grepFile(Map.of("pattern", "nonexistent", "path", filePath));

        assertTrue(result.contains("No files found"));
    }

    @Test
    void shouldFilterByIncludeWithDirectoryPrefix() throws IOException {
        write("src/Foo.java", "class Foo");
        write("src/Bar.java", "class Bar");
        write("test/Baz.java", "class Baz");
        write("readme.md", "class in md");

        var result = grep(Map.of("pattern", "class", "include", "src/*.java"));

        assertTrue(result.contains("src/Foo.java"));
        assertTrue(result.contains("src/Bar.java"));
    }

    @Test
    void shouldBuildCorrectly() {
        assertNotNull(tool);
        assertEquals("grep_file", tool.getName());
        assertNotNull(tool.getDescription());
        assertNotNull(tool.getParameters());
    }

    private String grep(Map<String, Object> args) {
        var mutable = new HashMap<>(args);
        mutable.putIfAbsent("path", dir.toString());
        return tool.execute(JSON.toJSON(mutable), ctx).getResult();
    }

    private String grepFile(Map<String, Object> args) {
        return tool.execute(JSON.toJSON(new HashMap<>(args)), ctx).getResult();
    }

    private void write(String relativePath, String content) throws IOException {
        var file = dir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
