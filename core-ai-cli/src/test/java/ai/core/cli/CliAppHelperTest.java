package ai.core.cli;

import ai.core.bootstrap.PropertiesFileSource;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.auth.RuntimeAuthConfig;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private AuthConfig auth() {
        return new AuthConfig(SERVER + "/", "coreai-key", "user-1", "Stephen", "user", "2026-10-19T00:00:00Z", true, false);
    }
}
