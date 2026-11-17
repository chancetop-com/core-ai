package ai.core.cli.config;

import ai.core.bootstrap.PropertiesFileSource;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.llm.providers.LiteLLMProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author stephen
 */
class ProviderWiringTest {
    @TempDir
    Path tempDir;

    private LlmProviderStore store() {
        return LlmProviderStore.load(tempDir.resolve("llm-providers.json"));
    }

    private void addLitellm(LLMProviders providers) {
        providers.addProvider(LLMProviderType.LITELLM,
                new LiteLLMProvider(new LLMProviderConfig("server-model", 0.7d, null), "http://localhost:4000", "k"));
    }

    @Test
    void profilesOverrideLegacyProvidersAndUseTheirOwnDefaultModel() {
        var store = store();
        var profile = LlmProviderProfile.preset(LLMProviderType.DEEPSEEK);
        profile.defaultModel = "deepseek-reasoner";
        store.saveProfile(profile);

        var providers = new LLMProviders();
        var props = new PropertiesFileSource(new Properties());
        ProviderWiring.applyProfiles(store, providers, props, null);

        var provider = providers.getProvider(LLMProviderType.DEEPSEEK);
        assertNotNull(provider);
        assertEquals("deepseek-reasoner", provider.config.getModel());
        assertNull(provider.config.getMultiModalModel());
    }

    @Test
    void defaultConnectionPrefersTheStoredProfile() {
        var store = store();
        store.saveProfile(LlmProviderProfile.preset(LLMProviderType.DEEPSEEK));
        store.setDefaultConnection("deepseek");
        var providers = new LLMProviders();
        addLitellm(providers);
        ProviderWiring.applyProfiles(store, providers, new PropertiesFileSource(new Properties()), null);

        assertEquals(LLMProviderType.DEEPSEEK, ProviderWiring.resolveDefaultType(store, providers,
                new PropertiesFileSource(new Properties()), true));
    }

    @Test
    void serverConnectionUsesLitellmWhenSignedIn() {
        var store = store();
        store.saveProfile(LlmProviderProfile.preset(LLMProviderType.DEEPSEEK));
        store.setDefaultConnection("server");
        var providers = new LLMProviders();
        addLitellm(providers);
        ProviderWiring.applyProfiles(store, providers, new PropertiesFileSource(new Properties()), null);

        assertEquals(LLMProviderType.LITELLM, ProviderWiring.resolveDefaultType(store, providers,
                new PropertiesFileSource(new Properties()), true));
    }

    @Test
    void legacyActiveProviderStillWorksWhenNoDefaultIsStored() {
        var store = store();
        store.saveProfile(LlmProviderProfile.preset(LLMProviderType.DEEPSEEK));
        var providers = new LLMProviders();
        addLitellm(providers);
        var properties = new Properties();
        properties.setProperty("active.provider", "litellm");
        var props = new PropertiesFileSource(properties);
        ProviderWiring.applyProfiles(store, providers, props, null);

        assertEquals(LLMProviderType.LITELLM,
                ProviderWiring.resolveDefaultType(store, providers, props, false));
    }

    @Test
    void firstProfileWinsWhenTheStoreHasNoDefault() {
        var store = store();
        store.saveProfile(LlmProviderProfile.preset(LLMProviderType.OPENROUTER));
        store.saveProfile(LlmProviderProfile.preset(LLMProviderType.DEEPSEEK));
        var providers = new LLMProviders();
        addLitellm(providers);
        ProviderWiring.applyProfiles(store, providers, new PropertiesFileSource(new Properties()), null);

        assertEquals(LLMProviderType.OPENROUTER, ProviderWiring.resolveDefaultType(store, providers,
                new PropertiesFileSource(new Properties()), true));
    }
}
