package ai.core.agent.formatter.formatters;

import ai.core.agent.planning.plannings.DefaultPlanningResult;
import core.framework.json.JSON;
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

    @Test
    void testFormatterWithNewLine() {
        var input = """
                {
                  "planning": "1. 使用coding-agent分析setDefaultButton有时候不生效的原因。",
                  "next_step": "TERMINATE",
                  "name": "coding-agent",
                  "query": "在文件 src/main/java/com/chancetop/naixt/plugin/idea/windows/OpenNaixtToolWindowFactory.java 中，用户在第54行，第61列使用了 setDefaultButton 方法，如下所示：
                
                mainPanel.getRootPane().setDefaultButton(sendButton);
                
                有时 setDefaultButton 似乎不生效，导致回车键不能触发发送按钮。请分析可能的原因并提供解决方案。"
                }""";
        var result = formatter.formatter(input);
        JSON.fromJSON(DefaultPlanningResult.class, result);
    }
}