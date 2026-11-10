package ai.core.server.gateway;

import ai.core.server.domain.GatewayProviderConfig;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author stephen
 */
class DecisionProviderAdapterFactoryTest {
    @Test
    void resolvesEveryProfilePathAndCredentialHeader() {
        assertEquals("/providers/microsoft/v1/systemone", DecisionProviderAdapterFactory.path("AZURE_FOUNDRY_DECISION"));
        assertEquals("api-key", DecisionProviderAdapterFactory.credentialHeader("AZURE_FOUNDRY_DECISION"));
        assertEquals("/v1/systemone", DecisionProviderAdapterFactory.path("SYSTEMONE"));
        assertEquals("Authorization", DecisionProviderAdapterFactory.credentialHeader("SYSTEMONE"));
        assertEquals("/api/alpha/decisions", DecisionProviderAdapterFactory.path("OPENROUTER_DECISIONS"));
        assertEquals("Authorization", DecisionProviderAdapterFactory.credentialHeader("OPENROUTER_DECISIONS"));
    }

    @Test
    void fallsBackToProviderTypeWhenNoProtocolIsConfigured() {
        assertEquals("AZURE_FOUNDRY_DECISION", DecisionProviderAdapterFactory.protocol(provider("azure")));
        assertEquals("SYSTEMONE", DecisionProviderAdapterFactory.protocol(provider("openai")));
        assertEquals("SYSTEMONE", DecisionProviderAdapterFactory.protocol(provider("openai-compatible")));
        assertEquals("OPENROUTER_DECISIONS", DecisionProviderAdapterFactory.protocol(provider("openrouter")));
    }

    @Test
    void requiresAProtocolForUnrelatedProviderTypes() {
        assertThrows(BadRequestException.class, () -> DecisionProviderAdapterFactory.protocol(provider("gemini")));
    }

    private GatewayProviderConfig provider(String type) {
        var provider = new GatewayProviderConfig();
        provider.id = "p";
        provider.type = type;
        return provider;
    }
}
