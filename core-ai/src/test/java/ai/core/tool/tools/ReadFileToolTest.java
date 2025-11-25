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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for ReadFileTool
 *
 * @author stephen
 */
class ReadFileToolTest {
    private final Logger logger = LoggerFactory.getLogger(ReadFileToolTest.class);
    private ReadFileTool readFileTool;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        readFileTool = ReadFileTool.builder().build();
    }

    @Test
    void testBuildToolCall() {
        ReadFileTool tool = ReadFileTool.builder().build();

        logger.info("ToolCall name: {}", tool.getName());
        assertNotNull(tool, "ToolCall should not be null");
        assertNotNull(tool.getName(), "ToolCall name should not be null");
        assertTrue(tool.getName().equals("read_file"), "ToolCall name should be 'read_file'");
    }

    @Test
    void testReadSimpleFile() throws IOException {
        // Create a test file
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Line 1\nLine 2\nLine 3\n");

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        String result = readFileTool.call(JSON.toJSON(args));

        logger.info("Read result:\n{}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Line 1"), "Result should contain Line 1");
        assertTrue(result.contains("Line 2"), "Result should contain Line 2");
        assertTrue(result.contains("Line 3"), "Result should contain Line 3");
        assertTrue(result.contains("→"), "Result should contain arrow separator");
    }

    @Test
    void testReadFileWithOffset() throws IOException {
        // Create a test file with multiple lines
        Path testFile = tempDir.resolve("test_offset.txt");
        Files.writeString(testFile, "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\n");

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("offset", 3);
        String result = readFileTool.call(JSON.toJSON(args));

        logger.info("Read with offset result:\n{}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(!result.contains("Line 1") && !result.contains("Line 2"),
            "Result should not contain lines before offset");
        assertTrue(result.contains("Line 3"), "Result should contain Line 3");
        assertTrue(result.contains("Line 4"), "Result should contain Line 4");
    }

    @Test
    void testReadFileWithLimit() throws IOException {
        // Create a test file with multiple lines
        Path testFile = tempDir.resolve("test_limit.txt");
        Files.writeString(testFile, "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\n");

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("limit", 2);
        String result = readFileTool.call(JSON.toJSON(args));

        logger.info("Read with limit result:\n{}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Line 1"), "Result should contain Line 1");
        assertTrue(result.contains("Line 2"), "Result should contain Line 2");
        assertTrue(!result.contains("Line 3"), "Result should not contain Line 3 due to limit");
    }

    @Test
    void testReadFileWithOffsetAndLimit() throws IOException {
        // Create a test file with multiple lines
        Path testFile = tempDir.resolve("test_offset_limit.txt");
        Files.writeString(testFile, "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\n");

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("offset", 2);
        args.put("limit", 2);
        String result = readFileTool.call(JSON.toJSON(args));

        logger.info("Read with offset and limit result:\n{}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(!result.contains("Line 1"), "Result should not contain Line 1");
        assertTrue(result.contains("Line 2"), "Result should contain Line 2");
        assertTrue(result.contains("Line 3"), "Result should contain Line 3");
        assertTrue(!result.contains("Line 4"), "Result should not contain Line 4 due to limit");
    }

    @Test
    void testReadNonExistentFile() {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", "/path/to/nonexistent/file.txt");
        String result = readFileTool.call(JSON.toJSON(args));

        logger.info("Non-existent file result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("does not exist"),
            "Result should indicate file does not exist");
    }

    @Test
    void testReadDirectory() throws IOException {
        // Create a directory
        Path testDir = tempDir.resolve("testdir");
        Files.createDirectory(testDir);

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testDir.toString());
        String result = readFileTool.call(JSON.toJSON(args));

        logger.info("Directory read result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("not a file"),
            "Result should indicate path is not a file");
    }

    @Test
    void testReadEmptyFile() throws IOException {
        // Create an empty file
        Path testFile = tempDir.resolve("empty.txt");
        Files.writeString(testFile, "");

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        String result = readFileTool.call(JSON.toJSON(args));

        logger.info("Empty file result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Warning") && result.contains("empty"),
            "Result should indicate file is empty");
    }

    @Test
    void testReadFileWithNullPath() {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", null);
        String result = readFileTool.call(JSON.toJSON(args));

        logger.info("Null path result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("required"),
            "Result should indicate file_path is required");
    }

    @Test
    void testReadFileWithEmptyPath() {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", "");
        String result = readFileTool.call(JSON.toJSON(args));

        logger.info("Empty path result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("required"),
            "Result should indicate file_path is required");
    }

    @Test
    void testReadFileWithLongLine() throws IOException {
        // Create a file with a very long line
        String longLine = "A".repeat(3000); // 3000 characters
        Path testFile = tempDir.resolve("long_line.txt");
        Files.writeString(testFile, longLine + "\nShort line\n");

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        String result = readFileTool.call(JSON.toJSON(args));

        logger.info("Long line result length: {}", result.length());
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("truncated"), "Result should indicate line truncation");
        assertTrue(result.contains("Short line"), "Result should contain the short line");
    }

    @Test
    void testOffsetBeyondFileLength() throws IOException {
        // Create a short file
        Path testFile = tempDir.resolve("short.txt");
        Files.writeString(testFile, "Line 1\nLine 2\n");

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("offset", 10);
        String result = readFileTool.call(JSON.toJSON(args));

        logger.info("Offset beyond file result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Warning") && result.contains("fewer lines"),
            "Result should indicate file has fewer lines than offset");
    }

    @Test
    void testLineNumberFormatting() throws IOException {
        // Create a test file
        Path testFile = tempDir.resolve("format.txt");
        Files.writeString(testFile, "Test line\n");

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        String result = readFileTool.call(JSON.toJSON(args));

        logger.info("Format result:\n{}", result);
        assertNotNull(result, "Result should not be null");
        // Check for cat -n style formatting with arrow
        assertTrue(result.contains("→"), "Result should contain arrow separator");
        assertTrue(result.contains("1→"), "Result should contain line number 1 with arrow");
        assertTrue(result.contains("Test line"), "Result should contain the test line");
    }
}
