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
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit test for WriteFileTool
 *
 * @author stephen
 */
class WriteFileToolTest {
    private final Logger logger = LoggerFactory.getLogger(WriteFileToolTest.class);
    private WriteFileTool writeFileTool;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        writeFileTool = WriteFileTool.builder().build();
    }

    @Test
    void testBuildToolCall() {
        WriteFileTool tool = WriteFileTool.builder().build();

        logger.info("ToolCall name: {}", tool.getName());
        assertNotNull(tool, "ToolCall should not be null");
        assertNotNull(tool.getName(), "ToolCall name should not be null");
        assertTrue(tool.getName().equals("write_file"), "ToolCall name should be 'write_file'");
    }

    @Test
    void testWriteNewFile() throws IOException {
        Path testFile = tempDir.resolve("new_file.txt");
        String content = "Hello, World!\nThis is a test file.";

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("content", content);
        String result = writeFileTool.execute(JSON.toJSON(args)).getResult();

        logger.info("Write new file result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Successfully created file"), "Result should indicate file was created");
        assertTrue(Files.exists(testFile), "File should exist");

        // Verify content
        String writtenContent = Files.readString(testFile);
        assertEquals(content, writtenContent, "Written content should match");
    }

    @Test
    void testOverwriteExistingFile() throws IOException {
        Path testFile = tempDir.resolve("existing_file.txt");
        Files.writeString(testFile, "Original content");

        String newContent = "New content";
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("content", newContent);
        String result = writeFileTool.execute(JSON.toJSON(args)).getResult();

        logger.info("Overwrite file result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Successfully overwrote file"), "Result should indicate file was overwritten");

        // Verify content
        String writtenContent = Files.readString(testFile);
        assertEquals(newContent, writtenContent, "Written content should match new content");
    }

    @Test
    void testWriteEmptyFile() throws IOException {
        Path testFile = tempDir.resolve("empty_file.txt");
        String content = "";

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("content", content);
        String result = writeFileTool.execute(JSON.toJSON(args)).getResult();

        logger.info("Write empty file result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Successfully"), "Result should indicate success");
        assertTrue(Files.exists(testFile), "File should exist");

        // Verify content
        String writtenContent = Files.readString(testFile);
        assertEquals(content, writtenContent, "Written content should be empty");
    }

    @Test
    void testWriteMultilineContent() throws IOException {
        Path testFile = tempDir.resolve("multiline.txt");
        String content = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5";

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("content", content);
        String result = writeFileTool.execute(JSON.toJSON(args)).getResult();

        logger.info("Write multiline result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Successfully"), "Result should indicate success");

        // Verify content
        String writtenContent = Files.readString(testFile);
        assertEquals(content, writtenContent, "Written content should match");
    }

    @Test
    void testWriteFileWithNullPath() {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", null);
        args.put("content", "Some content");
        String result = writeFileTool.execute(JSON.toJSON(args)).getResult();

        logger.info("Null path result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("required"),
            "Result should indicate file_path is required");
    }

    @Test
    void testWriteFileWithEmptyPath() {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", "");
        args.put("content", "Some content");
        String result = writeFileTool.execute(JSON.toJSON(args)).getResult();

        logger.info("Empty path result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("required"),
            "Result should indicate file_path is required");
    }

    @Test
    void testWriteFileWithNullContent() {
        Path testFile = tempDir.resolve("test.txt");

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("content", null);
        String result = writeFileTool.execute(JSON.toJSON(args)).getResult();

        logger.info("Null content result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("required"),
            "Result should indicate content is required");
    }

    @Test
    void testWriteFileWithNestedDirectories() throws IOException {
        Path nestedFile = tempDir.resolve("subdir1/subdir2/nested_file.txt");
        String content = "Nested file content";

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", nestedFile.toString());
        args.put("content", content);
        String result = writeFileTool.execute(JSON.toJSON(args)).getResult();

        logger.info("Write nested file result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Successfully"), "Result should indicate success");
        assertTrue(Files.exists(nestedFile), "Nested file should exist");

        // Verify parent directories were created
        assertTrue(Files.exists(nestedFile.getParent()), "Parent directory should exist");

        // Verify content
        String writtenContent = Files.readString(nestedFile);
        assertEquals(content, writtenContent, "Written content should match");
    }

    @Test
    void testWriteFileWithSpecialCharacters() throws IOException {
        Path testFile = tempDir.resolve("special_chars.txt");
        String content = "Special chars: \t\n\r\"'`~!@#$%^&*()_+-={}[]|\\:;<>?,./";

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("content", content);
        String result = writeFileTool.execute(JSON.toJSON(args)).getResult();

        logger.info("Write special chars result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Successfully"), "Result should indicate success");

        // Verify content
        String writtenContent = Files.readString(testFile);
        assertEquals(content, writtenContent, "Written content should match including special chars");
    }

    @Test
    void testWriteFileWithUnicodeContent() throws IOException {
        Path testFile = tempDir.resolve("unicode.txt");
        String content = "Unicode: 你好世界 🌍 Привет мир αβγδ";

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("content", content);
        String result = writeFileTool.execute(JSON.toJSON(args)).getResult();

        logger.info("Write unicode result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Successfully"), "Result should indicate success");

        // Verify content
        String writtenContent = Files.readString(testFile);
        assertEquals(content, writtenContent, "Written content should match including unicode");
    }

    @Test
    void testWriteLargeFile() throws IOException {
        Path testFile = tempDir.resolve("large_file.txt");
        // Create a large content (1MB)
        StringBuilder contentBuilder = new StringBuilder(64);
        for (int i = 0; i < 10000; i++) {
            contentBuilder.append("Line ").append(i).append(": This is a test line with some content.\n");
        }
        String content = contentBuilder.toString();

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("content", content);
        String result = writeFileTool.execute(JSON.toJSON(args)).getResult();

        logger.info("Write large file result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Successfully"), "Result should indicate success");
        assertTrue(Files.exists(testFile), "File should exist");

        // Verify content
        String writtenContent = Files.readString(testFile);
        assertEquals(content, writtenContent, "Written content should match");
        assertTrue(Files.size(testFile) > 100000, "File should be large");
    }

    @Test
    void testWriteFileReportsCharacterCount() throws IOException {
        Path testFile = tempDir.resolve("char_count.txt");
        String content = "12345";

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("content", content);
        String result = writeFileTool.execute(JSON.toJSON(args)).getResult();

        logger.info("Character count result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("5 characters"), "Result should report correct character count");
    }
}
