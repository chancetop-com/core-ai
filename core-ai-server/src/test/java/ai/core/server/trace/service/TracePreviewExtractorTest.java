package ai.core.server.trace.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Xander
 */
class TracePreviewExtractorTest {
    @Test
    void extractLastUserMessage() {
        var input = """
            {"messages": [
              {"role": "system", "content": "you are a helpful assistant"},
              {"role": "user", "content": "first question"},
              {"role": "assistant", "content": "first answer"},
              {"role": "user", "content": "second   question\\nwith newline"}
            ]}""";
        assertThat(TracePreviewExtractor.extract(input)).isEqualTo("second question with newline");
    }

    @Test
    void extractUserMessageWithContentParts() {
        var input = """
            {"messages": [
              {"role": "user", "content": [{"type": "text", "text": "hello from parts"}]}
            ]}""";
        assertThat(TracePreviewExtractor.extract(input)).isEqualTo("hello from parts");
    }

    @Test
    void extractFirstStringValueFromObject() {
        var input = "{\"query\": \"plain object input\", \"limit\": 5}";
        assertThat(TracePreviewExtractor.extract(input)).isEqualTo("plain object input");
    }

    @Test
    void fallBackToRawTextForNonJson() {
        assertThat(TracePreviewExtractor.extract("just a plain string")).isEqualTo("just a plain string");
    }

    @Test
    void truncateLongPreview() {
        var preview = TracePreviewExtractor.extract("x".repeat(500));
        assertThat(preview).hasSize(203).endsWith("...");
    }

    @Test
    void returnNullForBlankInput() {
        assertThat(TracePreviewExtractor.extract(null)).isNull();
        assertThat(TracePreviewExtractor.extract("  ")).isNull();
    }
}
