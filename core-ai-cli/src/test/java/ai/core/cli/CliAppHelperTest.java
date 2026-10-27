package ai.core.cli;

import ai.core.bootstrap.BootstrapResult;
import ai.core.bootstrap.PropertiesFileSource;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.auth.RuntimeAuthConfig;
import ai.core.llm.InlineImagePruner;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.llm.providers.LiteLLMProvider;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class CliAppHelperTest {
    private static final String SERVER = "https://core-ai.example.com";
    private static final String PROXY_BASE = SERVER + RuntimeAuthConfig.LLM_PROXY_PATH;

    @Test
    void injectsBothTheChatProxyAndTheMediaGatewayFromLogin() {
        var props = new PropertiesFileSource(new Properties());

        CliAppHelper.injectLiteLLMFallback(props, auth());

        assertEquals(PROXY_BASE, props.property("litellm.api.base").orElseThrow());
        assertEquals("coreai-key", props.property("litellm.api.key").orElseThrow());
        assertEquals(SERVER + "/api/gateway/v1", props.property("media.gateway.api.base").orElseThrow());
        assertEquals("coreai-key", props.property("media.gateway.api.key").orElseThrow());
    }

    @Test
    void keepsACustomLiteLLMProxyAndDoesNotDeriveTheMediaGateway() {
        var properties = new Properties();
        properties.setProperty("litellm.api.base", "https://proxy.example.com/v1");
        properties.setProperty("litellm.api.key", "proxy-key");
        var props = new PropertiesFileSource(properties);

        CliAppHelper.injectLiteLLMFallback(props, auth());

        assertEquals("https://proxy.example.com/v1", props.property("litellm.api.base").orElseThrow());
        assertEquals("proxy-key", props.property("litellm.api.key").orElseThrow());
        assertTrue(props.property("media.gateway.api.base").isEmpty());
    }

    @Test
    void derivesTheMediaGatewayWhenTheExplicitBaseIsTheServerProxy() {
        var properties = new Properties();
        properties.setProperty("litellm.api.base", PROXY_BASE + "/");
        var props = new PropertiesFileSource(properties);

        CliAppHelper.injectLiteLLMFallback(props, auth());

        assertEquals(PROXY_BASE, props.property("litellm.api.base").orElseThrow());
        assertEquals(SERVER + "/api/gateway/v1", props.property("media.gateway.api.base").orElseThrow());
    }

    @Test
    void keepsAnExplicitLiteLLMKeyWhenTheBaseMatchesTheServerProxy() {
        var properties = new Properties();
        properties.setProperty("litellm.api.base", PROXY_BASE);
        properties.setProperty("litellm.api.key", "explicit-key");
        var props = new PropertiesFileSource(properties);

        CliAppHelper.injectLiteLLMFallback(props, auth());

        assertEquals("explicit-key", props.property("litellm.api.key").orElseThrow());
        assertEquals("coreai-key", props.property("media.gateway.api.key").orElseThrow());
    }

    @Test
    void doesNotOverwriteAnExplicitMediaGateway() {
        var properties = new Properties();
        properties.setProperty("media.gateway.api.base", "https://other.example.com/api/gateway/v1");
        var props = new PropertiesFileSource(properties);

        CliAppHelper.injectLiteLLMFallback(props, auth());

        assertEquals("https://other.example.com/api/gateway/v1",
                props.property("media.gateway.api.base").orElseThrow());
    }

    @Test
    void parsesInlineImageByteBudgets() {
        assertEquals(4L * 1024 * 1024, CliAppHelper.parseByteSize("4MB"));
        assertEquals(4L * 1024 * 1024, CliAppHelper.parseByteSize("4m"));
        assertEquals(512L * 1024, CliAppHelper.parseByteSize("512 KB"));
        assertEquals(1048576L, CliAppHelper.parseByteSize("1048576"));
        assertEquals(-1L, CliAppHelper.parseByteSize("-1"));
        assertThrows(IllegalArgumentException.class, () -> CliAppHelper.parseByteSize("0"));
    }

    @Test
    void inlineImageBudgetPropertiesApplyToEveryProvider() {
        var properties = new Properties();
        properties.setProperty("agent.inline.image.max.count", "3");
        properties.setProperty("agent.inline.image.max.bytes", "2MB");
        var result = new BootstrapResult();
        result.llmProviders = new LLMProviders();
        var litellm = new LiteLLMProvider(new LLMProviderConfig(null, null, null), "http://localhost", "token");
        var openai = new LiteLLMProvider(new LLMProviderConfig(null, null, null), "http://localhost", "token");
        result.liteLLMProvider = litellm;
        result.llmProviders.addProvider(LLMProviderType.LITELLM, litellm);
        result.llmProviders.addProvider(LLMProviderType.OPENAI, openai);

        CliAppHelper.applyInlineImageLimits(result, new PropertiesFileSource(properties));

        assertEquals(3, litellm.config.getMaxInlineImages());
        assertEquals(2L * 1024 * 1024, litellm.config.getMaxInlineImageBytes());
        assertEquals(3, openai.config.getMaxInlineImages());
        assertEquals(2L * 1024 * 1024, openai.config.getMaxInlineImageBytes());
    }

    @Test
    void liteLLMKeepsItsProxyBudgetWhenNothingIsConfigured() {
        var result = new BootstrapResult();
        result.llmProviders = new LLMProviders();
        var litellm = new LiteLLMProvider(new LLMProviderConfig(null, null, null), "http://localhost", "token");
        var openai = new LiteLLMProvider(new LLMProviderConfig(null, null, null), "http://localhost", "token");
        result.liteLLMProvider = litellm;
        result.llmProviders.addProvider(LLMProviderType.LITELLM, litellm);
        result.llmProviders.addProvider(LLMProviderType.OPENAI, openai);

        CliAppHelper.applyInlineImageLimits(result, new PropertiesFileSource(new Properties()));

        assertEquals(4L * 1024 * 1024, litellm.config.getMaxInlineImageBytes());
        assertEquals(InlineImagePruner.DEFAULT_MAX_BYTES, openai.config.getMaxInlineImageBytes());
    }

    private AuthConfig auth() {
        return new AuthConfig(SERVER + "/", "coreai-key", "user-1", "Stephen", "user", "2026-10-19T00:00:00Z", true, false);
    }
}
