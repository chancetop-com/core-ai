package ai.core.cli.agent;

import ai.core.api.server.gateway.GatewayAvailableModelView;
import ai.core.cli.config.ModelRegistry;
import ai.core.llm.LLMProviderType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelPickerChoiceTest {

    @Test
    void serverModelsComeFirstAndTheActiveOneIsMarked() {
        var choices = ModelChoices.build("m2", LLMProviderType.LITELLM,
                List.of(new ModelRegistry.ModelEntry("local-a", LLMProviderType.OPENAI)),
                List.of(model("m1", "google"), model("m2", "azure")));

        assertEquals(3, choices.size());
        assertEquals("m1", choices.get(0).model());
        assertEquals("google", choices.get(0).providerLabel());
        assertTrue(choices.get(0).serverServed());
        assertFalse(choices.get(0).active());
        assertEquals("m2", choices.get(1).model());
        assertTrue(choices.get(1).active());
        assertEquals("local-a", choices.get(2).model());
        assertFalse(choices.get(2).serverServed());
    }

    @Test
    void localRegistryIsTheFallbackWhenTheHubIsUnavailable() {
        var choices = ModelChoices.build("local-a", LLMProviderType.OPENAI,
                List.of(new ModelRegistry.ModelEntry("local-a", LLMProviderType.OPENAI)), null);

        assertEquals(1, choices.size());
        assertEquals("openai", choices.getFirst().providerLabel());
        assertFalse(choices.getFirst().serverServed());
        assertTrue(choices.getFirst().active());
    }

    @Test
    void unknownCurrentModelGetsAProviderlessEntryInsteadOfFailing() {
        var choices = ModelChoices.build("switched-model", LLMProviderType.LITELLM,
                List.of(new ModelRegistry.ModelEntry("local-a", LLMProviderType.OPENAI)), null);

        assertEquals(2, choices.size());
        assertEquals("switched-model", choices.getFirst().model());
        assertEquals(null, choices.getFirst().providerLabel());
        assertTrue(choices.getFirst().active());
    }

    @Test
    void nullCurrentModelDoesNotCreateASyntheticEntry() {
        var choices = ModelChoices.build(null, LLMProviderType.LITELLM,
                List.of(new ModelRegistry.ModelEntry("local-a", LLMProviderType.LITELLM)), null);

        assertEquals(1, choices.size());
        assertEquals("local-a", choices.getFirst().model());
        assertFalse(choices.getFirst().active());
    }

    private GatewayAvailableModelView model(String id, String provider) {
        var model = new GatewayAvailableModelView();
        model.modelId = id;
        model.providerName = provider;
        return model;
    }
}
