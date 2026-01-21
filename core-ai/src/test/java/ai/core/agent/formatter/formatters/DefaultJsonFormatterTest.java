package ai.core.agent.formatter.formatters;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author stephen
 */
class DefaultJsonFormatterTest {
    private final DefaultJsonFormatter formatter = new DefaultJsonFormatter(true);

    @Test
    void testFormatterWithLeadingAndTrailingWhitespace() {
        var input = "   {\"key\": \"value\"}   ";
        var expected = "{\"key\": \"value\"}";
        var result = formatter.formatter(input);
        assertEquals(expected, result);
    }

    @Test
    void testFormatterWithTripleBackticksAndJsonIdentifier() {
        var input = "```json\n{\"key\": \"value\"}\n```";
        var expected = "{\"key\": \"value\"}";
        var result = formatter.formatter(input);
        assertEquals(expected, result);
    }

    @Test
    void testFormatterWithTripleBackticksOnly() {
        var input = "```\n{\"key\": \"value\"}\n```";
        var expected = "{\"key\": \"value\"}";
        var result = formatter.formatter(input);
        assertEquals(expected, result);
    }

    @Test
    void testFormatterWithMultipleTripleBackticksStartsWithContent() {
        var input = "Some text\n```json\n{\"key\": \"value\"}\n```";
        var expected = "{\"key\": \"value\"}";
        var result = formatter.formatter(input);
        assertEquals(expected, result);
    }

    @Test
    void testFormatterWithMultipleTripleBackticksEndWithContent() {
        var input = "```json\n{\"key\": \"value\"}\n```\nMore text";
        var expected = "{\"key\": \"value\"}";
        var result = formatter.formatter(input);
        assertEquals(expected, result);
    }

    @Test
    void testFormatterWithMultipleTripleBackticksWithContent() {
        var input = "Some text\n```\n{\"key\": \"value\"}\n```\nMore text";
        var expected = "Some text\n```\n{\"key\": \"value\"}\n```\nMore text";
        var result = formatter.formatter(input);
        assertEquals(expected, result);
    }

    @Test
    void testFormatterWithNoBackticks() {
        var input = "{\"key\": \"value\"}";
        var expected = "{\"key\": \"value\"}";
        var result = formatter.formatter(input);
        assertEquals(expected, result);
    }
}