package ai.core.server.trace.service;

import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.gateway.GatewayModelStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelPricingServiceTest {
    @Test
    void resolvesPriceByServingProvider() {
        var service = service(model("provider-1", 2D, 4D), model("provider-2", 1D, 2D));

        var price = service.resolve("deepseek-flash", "provider-1", 1_000_000L, 1_000_000L, 0L, null, null);

        assertEquals(6D, price.costUsd(), 1e-9);
        assertEquals("gateway_model", price.source());
        assertEquals("deepseek-flash", price.modelId());
    }

    @Test
    void fallsBackToAliasPriceWhenProviderUnknown() {
        var service = service(model("provider-1", 2D, 4D), model("provider-2", 1D, 2D));

        var price = service.resolve("deepseek-flash", 1_000_000L, 1_000_000L, 0L, null, null);

        assertEquals(3D, price.costUsd(), 1e-9);
        assertEquals("gateway_model", price.source());
    }

    private ModelPricingService service(GatewayModelConfig... models) {
        var store = mock(GatewayModelStore.class);
        when(store.listByModelId("deepseek-flash")).thenReturn(List.of(models[1], models[0]));
        when(store.listByProviderId("provider-1")).thenReturn(List.of(models[0]));
        when(store.listByProviderId("provider-2")).thenReturn(List.of(models[1]));
        var service = new ModelPricingService();
        service.gatewayModelStore = store;
        return service;
    }

    private GatewayModelConfig model(String providerId, double inputPrice, double outputPrice) {
        var model = new GatewayModelConfig();
        model.id = providerId + "-deepseek-flash";
        model.modelId = "deepseek-flash";
        model.providerId = providerId;
        model.inputPricePer1MTokens = inputPrice;
        model.outputPricePer1MTokens = outputPrice;
        return model;
    }
}
