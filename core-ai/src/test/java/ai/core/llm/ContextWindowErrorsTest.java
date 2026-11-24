package ai.core.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class ContextWindowErrorsTest {
    @Test
    void recognizesTheCommonProviderMessages() {
        assertTrue(ContextWindowErrors.matches(
                "This model's maximum context length is 1048576 tokens. However, you requested 1200000 tokens"));
        assertTrue(ContextWindowErrors.matches("prompt is too long: 210000 tokens > 200000 maximum"));
        assertTrue(ContextWindowErrors.matches(
                "The input token count (1100000) exceeds the maximum number of tokens allowed (1048576)."));
        assertTrue(ContextWindowErrors.matches("context_length_exceeded"));
        assertFalse(ContextWindowErrors.matches("invalid tool schema"));
        assertFalse(ContextWindowErrors.matches("statusCode=503"));
        assertFalse(ContextWindowErrors.matches(null));
    }

    @Test
    void extractsTheDeclaredLimit() {
        assertEquals(1048576, ContextWindowErrors.declaredLimit(
                "This model's maximum context length is 1048576 tokens. However, you requested 1200000 tokens"));
        assertEquals(200000, ContextWindowErrors.declaredLimit(
                "prompt is too long: 210000 tokens > 200000 maximum"));
        assertEquals(1048576, ContextWindowErrors.declaredLimit(
                "The input token count (1100000) exceeds the maximum number of tokens allowed (1048576)."));
        assertEquals(1_000_000, ContextWindowErrors.declaredLimit("context window is 1,000,000 tokens"));
        assertEquals(0, ContextWindowErrors.declaredLimit("context_length_exceeded"));
    }
}
