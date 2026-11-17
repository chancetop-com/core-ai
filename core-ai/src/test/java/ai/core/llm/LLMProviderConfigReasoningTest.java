package ai.core.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class LLMProviderConfigReasoningTest {
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void removeReasoningEffortKeepsOtherKeysAndDoesNotMutateTheSourceMap() {
        var config = new LLMProviderConfig("m", 0.7d, null);
        config.setRequestExtraBody("{\"reasoning_effort\":\"max\",\"top_p\":0.9}");
        config.addModelExtraBody("m", "{\"reasoning_effort\":\"max\",\"top_k\":5}");

        config.removeReasoningEffort();

        var request = asMap(config.getRequestExtraBody());
        assertFalse(request.containsKey("reasoning_effort"));
        assertEquals(0.9, request.get("top_p"));
        var model = asMap(config.resolveExtraBody("m"));
        assertFalse(model.containsKey("reasoning_effort"));
        assertEquals(5, model.get("top_k"));
    }

    @Test
    void mapReasoningEffortRewritesTheProviderSpecificValue() {
        var config = new LLMProviderConfig("m", 0.7d, null);
        config.setRequestExtraBody("{\"reasoning_effort\":\"max\"}");

        config.mapReasoningEffort(level -> "max".equals(level) ? "xhigh" : level);

        assertEquals("xhigh", asMap(config.getRequestExtraBody()).get("reasoning_effort"));
    }

    @Test
    void removeReasoningEffortLeavesAnEmptyMapWhenItWasTheOnlyKey() {
        var config = new LLMProviderConfig("m", 0.7d, null);
        config.setRequestExtraBody("{\"reasoning_effort\":\"max\"}");

        config.removeReasoningEffort();

        assertTrue(asMap(config.getRequestExtraBody()).isEmpty());
    }
}
