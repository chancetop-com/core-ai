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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class GlobFileToolTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobFileToolTest.class);
    private GlobFileTool globFileTool;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        globFileTool = GlobFileTool.builder().build();
    }

    @Test
    void testGlobJavaFiles() throws IOException {
        // Create test files
        createFile("Test.java");
        createFile("Example.java");
        createFile("Test.txt");
        createFile("subdir/Another.java");

        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "*.java");
        args.put("path", tempDir.toString());

        String result = globFileTool.execute(JSON.toJSON(args)).getResult();

        LOGGER.info("Glob *.java result: {}", result);
        assertNotNull(result);
        assertTrue(result.contains("Test.java"));
        assertTrue(result.contains("Example.java"));
        assertFalse(result.contains("Test.txt"));
    }

    @Test
    void testGlobRecursive() throws IOException {
        // Create test files in nested directories
        createFile("src/main.js");
        createFile("src/lib/util.js");
        createFile("src/test/test.js");
        createFile("README.md");
        createFile("package.json");

        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "**/*.js");
        args.put("path", tempDir.toString());

        String result = globFileTool.execute(JSON.toJSON(args)).getResult();

        LOGGER.info("Glob **/*.js result: {}", result);
        assertNotNull(result);
        assertTrue(result.contains("main.js"));
        assertTrue(result.contains("util.js"));
        assertTrue(result.contains("test.js"));
        assertFalse(result.contains("README.md"));
        assertFalse(result.contains("package.json"));
    }

    @Test
    void testGlobSpecificDirectory() throws IOException {
        // Create test files
        createFile("src/component/Button.tsx");
        createFile("src/component/Input.tsx");
        createFile("src/service/api.ts");
        createFile("test/Button.test.tsx");

        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "src/component/*.tsx");
        args.put("path", tempDir.toString());

        String result = globFileTool.execute(JSON.toJSON(args)).getResult();

        LOGGER.info("Glob src/component/*.tsx result: {}", result);
        assertNotNull(result);
        assertTrue(result.contains("Button.tsx"));
        assertTrue(result.contains("Input.tsx"));
        assertFalse(result.contains("api.ts"));
        assertFalse(result.contains("test"));
    }

    @Test
    void testGlobNoMatches() throws IOException {
        // Create test files
        createFile("test.txt");
        createFile("example.md");

        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "*.java");
        args.put("path", tempDir.toString());

        String result = globFileTool.execute(JSON.toJSON(args)).getResult();

        LOGGER.info("Glob no matches result: {}", result);
        assertNotNull(result);
        assertTrue(result.contains("No files found"));
    }

    @Test
    void testGlobWithoutPath() throws IOException {
        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "*.java");

        String result = globFileTool.execute(JSON.toJSON(args)).getResult();

        LOGGER.info("Glob without path result: {}", result);
        assertNotNull(result);
        // Should search in current directory
        assertTrue(result.contains("Found") || result.contains("No files found"));
    }

    @Test
    void testGlobNonExistentDirectory() {
        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "*.java");
        args.put("path", "/path/to/nonexistent/directory");

        String result = globFileTool.execute(JSON.toJSON(args)).getResult();

        LOGGER.info("Glob non-existent directory result: {}", result);
        assertNotNull(result);
        assertTrue(result.contains("Error") && result.contains("does not exist"));
    }

    @Test
    void testGlobPathIsFile() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "test");

        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "*.txt");
        args.put("path", file.toString());

        String result = globFileTool.execute(JSON.toJSON(args)).getResult();

        LOGGER.info("Glob path is file result: {}", result);
        assertNotNull(result);
        assertTrue(result.contains("Error") && result.contains("not a directory"));
    }

    @Test
    void testGlobWithNullPattern() {
        Map<String, Object> args = new HashMap<>();
        args.put("pattern", null);
        args.put("path", tempDir.toString());

        String result = globFileTool.execute(JSON.toJSON(args)).getResult();

        LOGGER.info("Glob null pattern result: {}", result);
        assertNotNull(result);
        assertTrue(result.contains("Error") && result.contains("required"));
    }

    @Test
    void testGlobMultipleExtensions() throws IOException {
        // Create test files
        createFile("component.tsx");
        createFile("service.ts");
        createFile("style.css");
        createFile("config.json");

        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "*.{tsx,ts}");
        args.put("path", tempDir.toString());

        String result = globFileTool.execute(JSON.toJSON(args)).getResult();

        LOGGER.info("Glob multiple extensions result: {}", result);
        assertNotNull(result);
        assertTrue(result.contains("component.tsx") || result.contains("service.ts"));
    }

    @Test
    void testGlobRootDirectory() throws IOException {
        // Create test files in root directory
        createFile("config.json");
        createFile("package.json");
        createFile("README.md");

        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "*.json");
        args.put("path", tempDir.toString());

        String result = globFileTool.execute(JSON.toJSON(args)).getResult();

        LOGGER.info("Glob *.json result: {}", result);
        assertNotNull(result);
        assertTrue(result.contains("config.json"));
        assertTrue(result.contains("package.json"));
        assertFalse(result.contains("README.md"));
    }

    @Test
    void testBuildToolCall() {
        var tool = GlobFileTool.builder().build();
        assertNotNull(tool);
        assertEquals("glob_file", tool.getName());
        assertNotNull(tool.getDescription());
        assertNotNull(tool.getParameters());
        LOGGER.info("ToolCall name: {}", tool.getName());
    }

    private void createFile(String relativePath) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "test content");
    }
}
