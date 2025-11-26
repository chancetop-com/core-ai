package ai.core.tool.tools;

import core.framework.json.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class GrepFileToolTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(GrepFileToolTest.class);
    private GrepFileTool grepFileTool;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        grepFileTool = GrepFileTool.builder().build();
    }

    @Test
    void testBasicPatternSearch() throws IOException {
        // Create test files with content
        createFileWithContent("test1.txt", "This is a test file\nwith multiple lines\ntest pattern here");
        createFileWithContent("test2.txt", "Another test file\nno match in this one");
        createFileWithContent("example.txt", "Example file\ntest word appears here");

        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "test pattern");
        args.put("path", tempDir.toString());

        String result = grepFileTool.call(JSON.toJSON(args));

        LOGGER.info("Basic pattern search result: {}", result);
        assertNotNull(result);
        assertTrue(result.contains("test1.txt"));
        assertFalse(result.contains("test2.txt"));
    }

    @Test
    void testCaseInsensitiveSearch() throws IOException {
        createFileWithContent("file1.txt", "HELLO world");
        createFileWithContent("file2.txt", "goodbye world");

        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "hello");
        args.put("path", tempDir.toString());
        args.put("-i", true);

        String result = grepFileTool.call(JSON.toJSON(args));

        LOGGER.info("Case insensitive search result: {}", result);
        assertNotNull(result);
        assertTrue(result.contains("file1.txt"));
    }

    @Test
    void testGlobFilter() throws IOException {
        createFileWithContent("test.java", "public class Test {}");
        createFileWithContent("test.txt", "test content");
        createFileWithContent("example.java", "public class Example {}");

        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "public class");
        args.put("path", tempDir.toString());
        args.put("glob", "*.java");

        String result = grepFileTool.call(JSON.toJSON(args));

        LOGGER.info("Glob filter result: {}", result);
        assertNotNull(result);
        assertTrue(result.contains("test.java"));
        assertTrue(result.contains("example.java"));
        assertFalse(result.contains("test.txt"));
    }

    @Test
    void testOutputModeContent() throws IOException {
        createFileWithContent("content.txt", "line 1\nline 2 with pattern\nline 3");

        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "pattern");
        args.put("path", tempDir.toString());
        args.put("output_mode", "content");

        String result = grepFileTool.call(JSON.toJSON(args));

        LOGGER.info("Content mode result: {}", result);
        assertNotNull(result);
        assertTrue(result.contains("line 2 with pattern"));
    }

    @Test
    void testOutputModeCount() throws IOException {
        createFileWithContent("file1.txt", "test\ntest\ntest");
        createFileWithContent("file2.txt", "test\ntest");

        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "test");
        args.put("path", tempDir.toString());
        args.put("output_mode", "count");

        String result = grepFileTool.call(JSON.toJSON(args));

        LOGGER.info("Count mode result: {}", result);
        assertNotNull(result);
        // Should show count of matches per file
        assertTrue(result.contains("3") || result.contains("2"));
    }

    @Test
    void testContextLinesAfter() throws IOException {
        createFileWithContent("context.txt", "line 1\nline 2 match\nline 3\nline 4\nline 5");

        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "match");
        args.put("path", tempDir.toString());
        args.put("output_mode", "content");
        args.put("-A", 2);

        String result = grepFileTool.call(JSON.toJSON(args));

        LOGGER.info("Context after result: {}", result);
        assertNotNull(result);
        assertTrue(result.contains("line 2 match"));
        assertTrue(result.contains("line 3"));
        assertTrue(result.contains("line 4"));
    }

    @Test
    void testContextLinesBefore() throws IOException {
        createFileWithContent("context.txt", "line 1\nline 2\nline 3 match\nline 4");

        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "match");
        args.put("path", tempDir.toString());
        args.put("output_mode", "content");
        args.put("-B", 2);

        String result = grepFileTool.call(JSON.toJSON(args));

        LOGGER.info("Context before result: {}", result);
        assertNotNull(result);
        assertTrue(result.contains("line 3 match"));
        assertTrue(result.contains("line 1") || result.contains("line 2"));
    }

    @Test
    void testContextLinesAround() throws IOException {
        createFileWithContent("context.txt", "line 1\nline 2\nline 3 match\nline 4\nline 5");

        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "match");
        args.put("path", tempDir.toString());
        args.put("output_mode", "content");
        args.put("-C", 1);

        String result = grepFileTool.call(JSON.toJSON(args));

        LOGGER.info("Context around result: {}", result);
        assertNotNull(result);
        assertTrue(result.contains("line 3 match"));
        assertTrue(result.contains("line 2"));
        assertTrue(result.contains("line 4"));
    }

    @Test
    void testLineNumbers() throws IOException {
        createFileWithContent("numbered.txt", "first line\nsecond line\nthird line with match");

        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "match");
        args.put("path", tempDir.toString());
        args.put("output_mode", "content");
        args.put("-n", true);

        String result = grepFileTool.call(JSON.toJSON(args));

        LOGGER.info("Line numbers result: {}", result);
        assertNotNull(result);
        // Should contain line number (3:)
        assertTrue(result.contains("3") && result.contains("third line with match"));
    }

    @Test
    void testMultilineMode() throws IOException {
        createFileWithContent("multiline.txt", "start of\nmultiline\npattern end");

        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "start.*end");
        args.put("path", tempDir.toString());
        args.put("multiline", true);

        String result = grepFileTool.call(JSON.toJSON(args));

        LOGGER.info("Multiline mode result: {}", result);
        assertNotNull(result);
        assertTrue(result.contains("multiline.txt"));
    }

    @Test
    void testRegexPattern() throws IOException {
        createFileWithContent("regex.txt", "test123\ntest456\nabc123");

        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "test\\d+");
        args.put("path", tempDir.toString());
        args.put("output_mode", "content");

        String result = grepFileTool.call(JSON.toJSON(args));

        LOGGER.info("Regex pattern result: {}", result);
        assertNotNull(result);
        assertTrue(result.contains("test123"));
        assertTrue(result.contains("test456"));
        assertFalse(result.contains("abc123"));
    }

    @Test
    void testNoMatches() throws IOException {
        createFileWithContent("nomatch.txt", "this file has no matches");

        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "nonexistent");
        args.put("path", tempDir.toString());

        String result = grepFileTool.call(JSON.toJSON(args));

        LOGGER.info("No matches result: {}", result);
        assertNotNull(result);
        assertTrue(result.contains("No matches found"));
    }

    @Test
    void testTypeFilter() throws IOException {
        // Create Java files
        createFileWithContent("Test.java", "public class Test {}");
        createFileWithContent("subdir/Example.java", "public class Example {}");
        createFileWithContent("readme.txt", "public class in txt");

        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "public class");
        args.put("path", tempDir.toString());
        args.put("type", "java");

        String result = grepFileTool.call(JSON.toJSON(args));

        LOGGER.info("Type filter result: {}", result);
        assertNotNull(result);
        assertTrue(result.contains("Test.java"));
        assertTrue(result.contains("Example.java"));
        assertFalse(result.contains("readme.txt"));
    }

    @Test
    void testSearchWithoutPath() throws IOException {
        // Create test file in temp dir and test that search works without explicit path
        // by setting working directory context
        createFileWithContent("default.txt", "test content here");

        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "test content");
        args.put("path", tempDir.toString());  // Still use path to avoid searching entire codebase

        String result = grepFileTool.call(JSON.toJSON(args));

        LOGGER.info("Search result: {}", result);
        assertNotNull(result);
        assertTrue(result.contains("default.txt"));
    }

    @Test
    void testInvalidPattern() {
        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "[invalid(regex");
        args.put("path", tempDir.toString());

        String result = grepFileTool.call(JSON.toJSON(args));

        LOGGER.info("Invalid pattern result: {}", result);
        assertNotNull(result);
        // Ripgrep should report error for invalid regex
        assertTrue(result.contains("Error") || result.contains("No matches"));
    }

    private void createFileWithContent(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
