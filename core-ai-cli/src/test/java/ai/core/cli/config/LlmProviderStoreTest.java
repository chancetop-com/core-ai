package ai.core.cli.config;

import ai.core.llm.LLMProviderType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class LlmProviderStoreTest {
    @TempDir
    Path tempDir;

    private Path file() {
        return tempDir.resolve(".core-ai").resolve("llm-providers.json");
    }

    @Test
    void savesAndReloadsAProfileUsingTheDocumentedJsonNames() throws Exception {
        var store = LlmProviderStore.load(file());
        var profile = LlmProviderProfile.preset(LLMProviderType.DEEPSEEK);
        profile.apiKey = "sk-test";
        store.saveProfile(profile);
        store.setDefaultConnection("deepseek");
        store.setServerModel("deepseek-v4-flash");

        var text = Files.readString(file());
        assertTrue(text.contains("\"default\""));
        assertFalse(text.contains("defaultConnection"));

        var reloaded = LlmProviderStore.load(file());
        assertEquals("deepseek", reloaded.defaultConnection());
        assertEquals("deepseek-v4-flash", reloaded.serverModel());
        var loaded = reloaded.profile(LLMProviderType.DEEPSEEK).orElseThrow();
        assertEquals("https://api.deepseek.com/v1", loaded.baseUrl);
        assertEquals("sk-test", loaded.apiKey);
        assertEquals("deepseek-chat", loaded.defaultModel);
        assertEquals("xhigh", loaded.reasoningEffortMap.get("max"));
        assertEquals("xhigh", loaded.wireEffort("max"));
    }

    @Test
    void replacingTheSameTypeKeepsOneProfile() {
        var store = LlmProviderStore.load(file());
        var first = LlmProviderProfile.preset(LLMProviderType.DEEPSEEK);
        first.baseUrl = "https://one.example/v1";
        store.saveProfile(first);
        var second = LlmProviderProfile.preset(LLMProviderType.DEEPSEEK);
        second.baseUrl = "https://two.example/v1";
        store.saveProfile(second);

        assertEquals(1, store.profiles().size());
        assertEquals("https://two.example/v1", store.profiles().getFirst().baseUrl);
    }

    @Test
    void rejectsUnsupportedTypesAndBadBaseUrls() {
        var store = LlmProviderStore.load(file());
        var azure = new LlmProviderProfile();
        azure.type = "azure";
        azure.baseUrl = "https://x/v1";
        assertThrows(IllegalArgumentException.class, () -> store.saveProfile(azure));

        var badBase = LlmProviderProfile.preset(LLMProviderType.OPENAI);
        badBase.baseUrl = "ftp://x";
        assertThrows(IllegalArgumentException.class, () -> store.saveProfile(badBase));
    }

    @Test
    void deletingTheDefaultClearsIt() {
        var store = LlmProviderStore.load(file());
        store.saveProfile(LlmProviderProfile.preset(LLMProviderType.DEEPSEEK));
        store.setDefaultConnection("deepseek");

        store.removeProfile("deepseek");

        assertTrue(store.profiles().isEmpty());
        assertNull(store.defaultConnection());
    }

    @Test
    void corruptFileIsTreatedAsEmptyInsteadOfCrashingTheEngine() throws Exception {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "{not-json");

        var store = LlmProviderStore.load(file());

        assertTrue(store.profiles().isEmpty());
        assertNull(store.defaultConnection());
    }
}
