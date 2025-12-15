package ai.core.agent.slashcommand;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class SlashCommandParserTest {

    @Test
    void testIsSlashCommand() {
        assertTrue(SlashCommandParser.isSlashCommand("/slash_command:read_file"));
        assertTrue(SlashCommandParser.isSlashCommand("/slash_command:tool_name:{}"));
        assertFalse(SlashCommandParser.isSlashCommand("normal query"));
        assertFalse(SlashCommandParser.isSlashCommand("/other_command:test"));
        assertFalse(SlashCommandParser.isSlashCommand(""));
    }

    @Test
    void testParseWithToolNameOnly() {
        var result = SlashCommandParser.parse("/slash_command:read_file");

        assertFalse(result.isNotValid());
        assertEquals("/slash_command:read_file", result.getOriginalQuery());
        assertEquals("read_file", result.getToolName());
        assertNull(result.getArguments());
        assertFalse(result.hasArguments());
    }

    @Test
    void testParseWithToolNameAndArguments() {
        var result = SlashCommandParser.parse("/slash_command:read_file:{\"file_path\": \"/path/to/file\"}");

        assertFalse(result.isNotValid());
        assertEquals("read_file", result.getToolName());
        assertEquals("{\"file_path\": \"/path/to/file\"}", result.getArguments());
        assertTrue(result.hasArguments());
    }

    @Test
    void testParseWithComplexArguments() {
        var query = "/slash_command:search:{\"query\": \"test:value\", \"limit\": 10}";
        var result = SlashCommandParser.parse(query);

        assertFalse(result.isNotValid());
        assertEquals("search", result.getToolName());
        assertEquals("{\"query\": \"test:value\", \"limit\": 10}", result.getArguments());
    }

    @Test
    void testParseWithEmptyArguments() {
        var result = SlashCommandParser.parse("/slash_command:tool_name:");

        assertFalse(result.isNotValid());
        assertEquals("tool_name", result.getToolName());
        assertNull(result.getArguments());
        assertFalse(result.hasArguments());
    }

    @Test
    void testParseInvalidNoPrefix() {
        var result = SlashCommandParser.parse("normal query");

        assertTrue(result.isNotValid());
        assertEquals("normal query", result.getOriginalQuery());
    }

    @Test
    void testParseInvalidEmptyToolName() {
        var result = SlashCommandParser.parse("/slash_command:");

        assertTrue(result.isNotValid());
    }

    @Test
    void testParseInvalidEmptyAfterPrefix() {
        var result = SlashCommandParser.parse("/slash_command:");

        assertTrue(result.isNotValid());
    }

    @Test
    void testParseWithSpacesInToolName() {
        var result = SlashCommandParser.parse("/slash_command: read_file :{\"path\": \"test\"}");

        assertFalse(result.isNotValid());
        assertEquals("read_file", result.getToolName());
        assertEquals("{\"path\": \"test\"}", result.getArguments());
    }

    @Test
    void testParseWithNestedJsonArguments() {
        var query = "/slash_command:complex_tool:{\"data\": {\"nested\": {\"value\": 123}}}";
        var result = SlashCommandParser.parse(query);

        assertFalse(result.isNotValid());
        assertEquals("complex_tool", result.getToolName());
        assertEquals("{\"data\": {\"nested\": {\"value\": 123}}}", result.getArguments());
    }
}
