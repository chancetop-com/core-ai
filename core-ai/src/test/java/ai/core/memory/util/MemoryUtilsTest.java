package ai.core.memory.util;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for MemoryUtils.
 *
 * @author xander
 */
class MemoryUtilsTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryUtilsTest.class);

    @Test
    void testCleanJsonSimple() {
        String input = "{\"key\": \"value\"}";
        String result = MemoryUtils.cleanJson(input);

        assertEquals("{\"key\": \"value\"}", result);
        LOGGER.info("cleanJson simple test passed");
    }

    @Test
    void testCleanJsonWithMarkdownJsonBlock() {
        String input = "```json\n{\"key\": \"value\"}\n```";
        String result = MemoryUtils.cleanJson(input);

        assertEquals("{\"key\": \"value\"}", result);
        LOGGER.info("cleanJson with markdown json block test passed");
    }

    @Test
    void testCleanJsonWithMarkdownBlock() {
        String input = "```\n{\"key\": \"value\"}\n```";
        String result = MemoryUtils.cleanJson(input);

        assertEquals("{\"key\": \"value\"}", result);
        LOGGER.info("cleanJson with markdown block test passed");
    }

    @Test
    void testCleanJsonWithWhitespace() {
        String input = "  ```json\n{\"key\": \"value\"}\n```  ";
        String result = MemoryUtils.cleanJson(input);

        assertEquals("{\"key\": \"value\"}", result);
        LOGGER.info("cleanJson with whitespace test passed");
    }

    @Test
    void testCleanJsonNull() {
        String result = MemoryUtils.cleanJson(null);

        assertEquals("{}", result);
        LOGGER.info("cleanJson null test passed");
    }

    @Test
    void testTruncateShortString() {
        String input = "Hello";
        String result = MemoryUtils.truncate(input, 10);

        assertEquals("Hello", result);
        LOGGER.info("truncate short string test passed");
    }

    @Test
    void testTruncateLongString() {
        String input = "This is a very long string that needs to be truncated";
        String result = MemoryUtils.truncate(input, 20);

        assertEquals("This is a very long ...", result);
        assertEquals(23, result.length()); // 20 chars + "..."
        LOGGER.info("truncate long string test passed");
    }

    @Test
    void testTruncateExactLength() {
        String input = "Exactly10!";
        String result = MemoryUtils.truncate(input, 10);

        assertEquals("Exactly10!", result);
        LOGGER.info("truncate exact length test passed");
    }

    @Test
    void testTruncateNull() {
        String result = MemoryUtils.truncate(null, 10);

        assertEquals("", result);
        LOGGER.info("truncate null test passed");
    }

    @Test
    void testTruncateDefault() {
        String input = "Short";
        String result = MemoryUtils.truncate(input);

        assertEquals("Short", result);
        LOGGER.info("truncate default test passed");
    }

    @Test
    void testTruncateDefaultLong() {
        String input = "This is a string that is definitely longer than fifty characters and should be truncated";
        String result = MemoryUtils.truncate(input);

        assertEquals(53, result.length()); // 50 chars + "..."
        assertTrue(result.endsWith("..."));
        LOGGER.info("truncate default long test passed");
    }

    @Test
    void testEscapeJsonSimple() {
        String input = "Hello World";
        String result = MemoryUtils.escapeJson(input);

        assertEquals("Hello World", result);
        LOGGER.info("escapeJson simple test passed");
    }

    @Test
    void testEscapeJsonWithQuotes() {
        String input = "He said \"Hello\"";
        String result = MemoryUtils.escapeJson(input);

        assertEquals("He said \\\"Hello\\\"", result);
        LOGGER.info("escapeJson with quotes test passed");
    }

    @Test
    void testEscapeJsonWithNewlines() {
        String input = "Line1\nLine2\rLine3";
        String result = MemoryUtils.escapeJson(input);

        assertEquals("Line1\\nLine2\\rLine3", result);
        LOGGER.info("escapeJson with newlines test passed");
    }

    @Test
    void testEscapeJsonWithTabs() {
        String input = "Col1\tCol2";
        String result = MemoryUtils.escapeJson(input);

        assertEquals("Col1\\tCol2", result);
        LOGGER.info("escapeJson with tabs test passed");
    }

    @Test
    void testEscapeJsonWithBackslash() {
        String input = "Path\\to\\file";
        String result = MemoryUtils.escapeJson(input);

        assertEquals("Path\\\\to\\\\file", result);
        LOGGER.info("escapeJson with backslash test passed");
    }

    @Test
    void testEscapeJsonNull() {
        String result = MemoryUtils.escapeJson(null);

        assertEquals("", result);
        LOGGER.info("escapeJson null test passed");
    }

    @Test
    void testEscapeJsonCombined() {
        String input = "Say \"Hello\"\nPath: C:\\Users";
        String result = MemoryUtils.escapeJson(input);

        assertEquals("Say \\\"Hello\\\"\\nPath: C:\\\\Users", result);
        LOGGER.info("escapeJson combined test passed");
    }

    private void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("Expected true but was false");
        }
    }
}
