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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for EditFileTool
 *
 * @author stephen
 */
class EditFileToolTest {
    private final Logger logger = LoggerFactory.getLogger(EditFileToolTest.class);
    private EditFileTool editFileTool;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        editFileTool = EditFileTool.builder().build();
    }

    @Test
    void testBuildToolCall() {
        EditFileTool tool = EditFileTool.builder().build();

        logger.info("ToolCall name: {}", tool.getName());
        assertNotNull(tool, "ToolCall should not be null");
        assertNotNull(tool.getName(), "ToolCall name should not be null");
        assertTrue(tool.getName().equals("edit_file"), "ToolCall name should be 'edit_file'");
    }

    @Test
    void testSimpleEdit() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        String originalContent = "Hello World\nThis is a test\nGoodbye World";
        Files.writeString(testFile, originalContent);

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("old_string", "Hello World");
        args.put("new_string", "Hi Universe");
        String result = editFileTool.call(JSON.toJSON(args));

        logger.info("Simple edit result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Successfully replaced 1 occurrence"), "Result should indicate success");

        // Verify content
        String newContent = Files.readString(testFile);
        assertEquals("Hi Universe\nThis is a test\nGoodbye World", newContent, "Content should be updated");
    }

    @Test
    void testEditMultilineString() throws IOException {
        Path testFile = tempDir.resolve("multiline.txt");
        String originalContent = "Line 1\nLine 2\nLine 3\nLine 4";
        Files.writeString(testFile, originalContent);

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("old_string", "Line 2\nLine 3");
        args.put("new_string", "Updated Line 2\nUpdated Line 3");
        String result = editFileTool.call(JSON.toJSON(args));

        logger.info("Multiline edit result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Successfully"), "Result should indicate success");

        // Verify content
        String newContent = Files.readString(testFile);
        assertEquals("Line 1\nUpdated Line 2\nUpdated Line 3\nLine 4", newContent, "Content should be updated");
    }

    @Test
    void testReplaceAllOccurrences() throws IOException {
        Path testFile = tempDir.resolve("replace_all.txt");
        String originalContent = "foo bar foo baz foo";
        Files.writeString(testFile, originalContent);

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("old_string", "foo");
        args.put("new_string", "qux");
        args.put("replace_all", true);
        String result = editFileTool.call(JSON.toJSON(args));

        logger.info("Replace all result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Successfully replaced 3 occurrence(s)"), "Result should indicate 3 replacements");

        // Verify content
        String newContent = Files.readString(testFile);
        assertEquals("qux bar qux baz qux", newContent, "All occurrences should be replaced");
    }

    @Test
    void testEditFailsWithMultipleOccurrencesWithoutReplaceAll() throws IOException {
        Path testFile = tempDir.resolve("multiple.txt");
        String originalContent = "foo bar foo baz";
        Files.writeString(testFile, originalContent);

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("old_string", "foo");
        args.put("new_string", "qux");
        args.put("replace_all", false);
        String result = editFileTool.call(JSON.toJSON(args));

        logger.info("Multiple occurrences result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("appears 2 times"),
            "Result should indicate multiple occurrences error");

        // Verify content unchanged
        String content = Files.readString(testFile);
        assertEquals(originalContent, content, "Content should remain unchanged");
    }

    @Test
    void testEditNonExistentString() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Hello World");

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("old_string", "Goodbye");
        args.put("new_string", "Hi");
        String result = editFileTool.call(JSON.toJSON(args));

        logger.info("Non-existent string result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("not found"),
            "Result should indicate string not found");
    }

    @Test
    void testEditNonExistentFile() {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", "/path/to/nonexistent/file.txt");
        args.put("old_string", "old");
        args.put("new_string", "new");
        String result = editFileTool.call(JSON.toJSON(args));

        logger.info("Non-existent file result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("does not exist"),
            "Result should indicate file does not exist");
    }

    @Test
    void testEditWithNullFilePath() {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", null);
        args.put("old_string", "old");
        args.put("new_string", "new");
        String result = editFileTool.call(JSON.toJSON(args));

        logger.info("Null file path result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("required"),
            "Result should indicate file_path is required");
    }

    @Test
    void testEditWithNullOldString() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Hello World");

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("old_string", null);
        args.put("new_string", "new");
        String result = editFileTool.call(JSON.toJSON(args));

        logger.info("Null old_string result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("old_string") && result.contains("required"),
            "Result should indicate old_string is required");
    }

    @Test
    void testEditWithNullNewString() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Hello World");

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("old_string", "Hello");
        args.put("new_string", null);
        String result = editFileTool.call(JSON.toJSON(args));

        logger.info("Null new_string result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("new_string") && result.contains("required"),
            "Result should indicate new_string is required");
    }

    @Test
    void testEditWithSameOldAndNewString() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Hello World");

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("old_string", "Hello");
        args.put("new_string", "Hello");
        String result = editFileTool.call(JSON.toJSON(args));

        logger.info("Same strings result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("must be different"),
            "Result should indicate strings must be different");
    }

    @Test
    void testEditWithWhitespace() throws IOException {
        Path testFile = tempDir.resolve("whitespace.txt");
        String originalContent = "    public void test() {\n        System.out.println(\"test\");\n    }";
        Files.writeString(testFile, originalContent);

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("old_string", "    public void test()");
        args.put("new_string", "    public void testMethod()");
        String result = editFileTool.call(JSON.toJSON(args));

        logger.info("Whitespace edit result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Successfully"), "Result should indicate success");

        // Verify content
        String newContent = Files.readString(testFile);
        assertTrue(newContent.contains("public void testMethod()"), "Content should be updated");
    }

    @Test
    void testEditDirectory() throws IOException {
        Path testDir = tempDir.resolve("testdir");
        Files.createDirectory(testDir);

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testDir.toString());
        args.put("old_string", "old");
        args.put("new_string", "new");
        String result = editFileTool.call(JSON.toJSON(args));

        logger.info("Edit directory result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("not a file"),
            "Result should indicate path is not a file");
    }

    @Test
    void testEditWithSpecialCharacters() throws IOException {
        Path testFile = tempDir.resolve("special.txt");
        String originalContent = "String str = \"Hello\\nWorld\";";
        Files.writeString(testFile, originalContent);

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("old_string", "\"Hello\\nWorld\"");
        args.put("new_string", "\"Hi\\nUniverse\"");
        String result = editFileTool.call(JSON.toJSON(args));

        logger.info("Special characters edit result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Successfully"), "Result should indicate success");

        // Verify content
        String newContent = Files.readString(testFile);
        assertTrue(newContent.contains("Hi\\nUniverse"), "Content should be updated");
    }

    @Test
    void testEditPreservesEncoding() throws IOException {
        Path testFile = tempDir.resolve("unicode.txt");
        String originalContent = "Unicode: 你好世界 🌍";
        Files.writeString(testFile, originalContent);

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("old_string", "你好世界");
        args.put("new_string", "Hello World");
        String result = editFileTool.call(JSON.toJSON(args));

        logger.info("Unicode edit result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Successfully"), "Result should indicate success");

        // Verify content
        String newContent = Files.readString(testFile);
        assertEquals("Unicode: Hello World 🌍", newContent, "Content should preserve unicode");
    }

    @Test
    void testEditLargeString() throws IOException {
        Path testFile = tempDir.resolve("large.txt");
        String largeBlock = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5";
        String originalContent = "Before\n" + largeBlock + "\nAfter";
        Files.writeString(testFile, originalContent);

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", testFile.toString());
        args.put("old_string", largeBlock);
        args.put("new_string", "Updated Block");
        String result = editFileTool.call(JSON.toJSON(args));

        logger.info("Large string edit result: {}", result);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Successfully"), "Result should indicate success");

        // Verify content
        String newContent = Files.readString(testFile);
        assertEquals("Before\nUpdated Block\nAfter", newContent, "Large block should be replaced");
    }
}
