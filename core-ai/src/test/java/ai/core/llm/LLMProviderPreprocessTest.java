package ai.core.llm;

import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Request rewrites applied by model family before the call goes upstream.
 *
 * @author stephen
 */
class LLMProviderPreprocessTest {
    @Test
    void requestedTemperatureIsSentUpstream() {
        var provider = new LLMProviderModalityTest.RecordingProvider();

        provider.completion(request("deepseek-v4-pro", 0.3));

        assertEquals(0.3, provider.seenTemperature);
    }

    @Test
    void missingRequestTemperatureFallsBackToProviderConfig() {
        var provider = new LLMProviderModalityTest.RecordingProvider();

        provider.completion(request("deepseek-v4-pro", null));

        assertEquals(0.7, provider.seenTemperature);
    }

    @Test
    void o1ModelNeverCarriesATemperature() {
        var provider = new LLMProviderModalityTest.RecordingProvider();

        provider.completion(request("o1-mini", 0.7));

        assertNull(provider.seenTemperature);
    }

    @Test
    void stripHistoricalReasoningContentDropsPriorTurnsAndEmptyStringsWithoutMutatingOriginal() {
        var priorAssistant = Message.of(RoleType.ASSISTANT, "first answer", null, null, null, "prior chain of thought");
        var activeAssistant = Message.of(RoleType.ASSISTANT, "", null, null, null, "current tool reasoning");
        var emptyReasoningAssistant = Message.of(RoleType.ASSISTANT, "ok", null, null, null, "");
        var original = CompletionRequest.of(List.of(
                Message.of(RoleType.SYSTEM, "sys"),
                Message.of(RoleType.USER, "turn 1"),
                priorAssistant,
                Message.of(RoleType.USER, "turn 2"),
                activeAssistant,
                emptyReasoningAssistant
        ), List.of(), 0.3, "deepseek-flash", "test");

        var projected = LLMProvider.stripHistoricalReasoningContent(original);

        assertEquals("prior chain of thought", priorAssistant.reasoningContent);
        assertNull(projected.messages.get(2).reasoningContent);
        assertEquals("current tool reasoning", projected.messages.get(4).reasoningContent);
        assertNull(projected.messages.get(5).reasoningContent);
    }

    private CompletionRequest request(String model, Double temperature) {
        return CompletionRequest.of(List.of(Message.of(RoleType.USER, "hi")), List.of(), temperature, model, "test");
    }
}
