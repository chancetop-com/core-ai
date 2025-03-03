package ai.core.agent;

import ai.core.agent.formatter.formatters.DefaultJsonFormatter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author stephen
 */
class DefaultJsonFormatterTest {
    private final DefaultJsonFormatter formatter = new DefaultJsonFormatter(true);

    @Test
    void testFormatterWithLeadingAndTrailingWhitespace() {
        String input = "   {\"key\": \"value\"}   ";
        String expected = "{\"key\": \"value\"}";
        String result = formatter.formatter(input);
        assertEquals(expected, result);
    }

    @Test
    void testFormatterWithTripleBackticksAndJsonIdentifier() {
        String input = "```json\n{\"key\": \"value\"}\n```";
        String expected = "{\"key\": \"value\"}";
        String result = formatter.formatter(input);
        assertEquals(expected, result);
    }

    @Test
    void testFormatterWithTripleBackticksOnly() {
        String input = "```\n{\"key\": \"value\"}\n```";
        String expected = "{\"key\": \"value\"}";
        String result = formatter.formatter(input);
        assertEquals(expected, result);
    }

    @Test
    void testFormatterWithMultipleTripleBackticksStartsWithContent() {
        String input = "Some text\n```json\n{\"key\": \"value\"}\n```";
        String expected = "{\"key\": \"value\"}";
        String result = formatter.formatter(input);
        assertEquals(expected, result);
    }

    @Test
    void testFormatterWithMultipleTripleBackticksEndWithContent() {
        String input = "```json\n{\"key\": \"value\"}\n```\nMore text";
        String expected = "{\"key\": \"value\"}";
        String result = formatter.formatter(input);
        assertEquals(expected, result);
    }

    @Test
    void testFormatterWithMultipleTripleBackticksWithContent() {
        String input = "Some text\n```\n{\"key\": \"value\"}\n```\nMore text";
        String expected = "Some text\n```\n{\"key\": \"value\"}\n```\nMore text";
        String result = formatter.formatter(input);
        assertEquals(expected, result);
    }

    @Test
    void testFormatterWithNoBackticks() {
        String input = "{\"key\": \"value\"}";
        String expected = "{\"key\": \"value\"}";
        String result = formatter.formatter(input);
        assertEquals(expected, result);
    }
}