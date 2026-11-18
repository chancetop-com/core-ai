package ai.core.server.analytics;

import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.gateway.GatewayModelStore;
import ai.core.server.gateway.GatewayProviderStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A trace stores the model name its caller used, which is either the plain {@code modelId} or
 * {@code provider.modelPrefix + modelId}. Missing the prefixed form is what made analytics report
 * {@code provider_id=unknown} for every {@code deepseek/deepseek-v4-flash} style call.
 *
 * @author stephen
 */
class AnalyticsMappingServiceTest {
    private static GatewayProviderConfig provider(String id, String modelPrefix) {
        var provider = new GatewayProviderConfig();
        provider.id = id;
        provider.name = id;
        provider.modelPrefix = modelPrefix;
        return provider;
    }

    private static GatewayModelConfig model(String modelId, String providerId, boolean enabled) {
        var model = new GatewayModelConfig();
        model.id = modelId + "-" + providerId;
        model.modelId = modelId;
        model.providerId = providerId;
        model.enabled = enabled;
        return model;
    }

    private AnalyticsMappingService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsMappingService();
        var modelStore = mock(GatewayModelStore.class);
        var providerStore = mock(GatewayProviderStore.class);
        service.gatewayModelStore = modelStore;
        service.gatewayProviderStore = providerStore;

        when(providerStore.listAll()).thenReturn(List.of(
            provider("p-deepseek", "deepseek/"),
            provider("p-azure", "azure/"),
            provider("p-kie", "kie/"),
            provider("p-plain", null)
        ));
        when(modelStore.listAll()).thenReturn(List.of(
            model("deepseek-v4-flash", "p-deepseek", true),
            model("deepseek-v4-pro", "p-deepseek", true),
            model("gpt-5.2", "p-azure", true),
            model("gpt-6.1-sol", "p-kie", false),
            model("gpt-6.1-sol", "p-azure", true),
            model("local-model", "p-plain", true)
        ));
    }

    @Test
    void mapsPlainModelId() {
        assertEquals("p-deepseek", service.loadModelToProviderMapping().get("deepseek-v4-flash"));
    }

    @Test
    void mapsProviderPrefixedModelName() {
        var mapping = service.loadModelToProviderMapping();
        assertEquals("p-deepseek", mapping.get("deepseek/deepseek-v4-flash"));
        assertEquals("p-deepseek", mapping.get("deepseek/deepseek-v4-pro"));
        assertEquals("p-azure", mapping.get("azure/gpt-5.2"));
    }

    @Test
    void enabledRegistrationWinsOverDisabled() {
        assertEquals("p-azure", service.loadModelToProviderMapping().get("gpt-6.1-sol"));
    }

    @Test
    void onlyAddsAliasesForProviderWithModelPrefix() {
        var mapping = service.loadModelToProviderMapping();
        assertEquals(10, mapping.size());
        assertEquals("p-plain", mapping.get("local-model"));
    }
}
