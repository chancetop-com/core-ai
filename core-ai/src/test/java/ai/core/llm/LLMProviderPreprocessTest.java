package ai.core.llm;

import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Request rewrites applied by model family before the call goes upstream.
 *
 * @author stephen
 */
class LLMProviderPreprocessTest {
    @Test
    void gpt6ModelUsesTheDefaultTemperature() {
        var provider = new LLMProviderModalityTest.RecordingProvider();

        provider.completion(request("gpt-6.1-sol", 0.7));

        assertEquals(1.0, provider.seenTemperature);
    }

    @Test
    void gpt6ModelWithoutRequestTemperatureStillUsesTheDefault() {
        var provider = new LLMProviderModalityTest.RecordingProvider();

        provider.completion(request("gpt-6-luna", null));

        assertEquals(1.0, provider.seenTemperature);
    }

    @Test
    void otherModelsKeepTheRequestedTemperature() {
        var provider = new LLMProviderModalityTest.RecordingProvider();

        provider.completion(request("deepseek-v4-pro", 0.3));

        assertEquals(0.3, provider.seenTemperature);
    }

    @Test
    void otherModelsWithoutRequestTemperatureFallBackToProviderConfig() {
        var provider = new LLMProviderModalityTest.RecordingProvider();

        provider.completion(request("deepseek-v4-pro", null));

        assertEquals(0.7, provider.seenTemperature);
    }

    private CompletionRequest request(String model, Double temperature) {
        return CompletionRequest.of(List.of(Message.of(RoleType.USER, "hi")), List.of(), temperature, model, "test");
    }
}
