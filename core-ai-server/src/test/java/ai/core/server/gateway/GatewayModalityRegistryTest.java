package ai.core.server.gateway;

import ai.core.llm.InputModality;
import ai.core.llm.ModalitySupport;
import ai.core.server.domain.GatewayModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Xander
 */
class GatewayModalityRegistryTest {
    private GatewayRoutingEngine routingEngine;
    private GatewayModalityRegistry registry;

    @BeforeEach
    void setUp() {
        routingEngine = mock(GatewayRoutingEngine.class);
        registry = new GatewayModalityRegistry(routingEngine);
    }

    @Test
    void textIsAlwaysSupported() {
        assertEquals(ModalitySupport.SUPPORTED, registry.supports("any-model", InputModality.TEXT));
    }

    @Test
    void declaredVisionTrueWins() {
        var config = new GatewayModelConfig();
        config.supportsVision = Boolean.TRUE;
        when(routingEngine.modelConfig("my-model")).thenReturn(config);

        assertEquals(ModalitySupport.SUPPORTED, registry.supports("my-model", InputModality.IMAGE));
    }

    @Test
    void declaredVisionFalseWins() {
        var config = new GatewayModelConfig();
        config.supportsVision = Boolean.FALSE;
        config.upstreamModel = "azure/gpt-5-mini";
        when(routingEngine.modelConfig("my-model")).thenReturn(config);

        assertEquals(ModalitySupport.UNSUPPORTED, registry.supports("my-model", InputModality.IMAGE));
    }

    @Test
    void nullDeclarationFallsBackToSeedByUpstreamModel() {
        var config = new GatewayModelConfig();
        config.upstreamModel = "deepseek/deepseek-v4-flash";
        when(routingEngine.modelConfig("my-model")).thenReturn(config);

        assertEquals(ModalitySupport.UNSUPPORTED, registry.supports("my-model", InputModality.IMAGE));
    }

    @Test
    void unknownGatewayModelFallsBackToSeedByRequestedName() {
        when(routingEngine.modelConfig("deepseek/deepseek-v4-flash")).thenReturn(null);
        when(routingEngine.modelConfig("no-such-model")).thenReturn(null);

        assertEquals(ModalitySupport.UNSUPPORTED, registry.supports("deepseek/deepseek-v4-flash", InputModality.IMAGE));
        assertEquals(ModalitySupport.UNKNOWN, registry.supports("no-such-model", InputModality.IMAGE));
    }

    @Test
    void declaredFileSupportWins() {
        var config = new GatewayModelConfig();
        config.supportsFile = Boolean.TRUE;
        when(routingEngine.modelConfig("my-model")).thenReturn(config);

        assertEquals(ModalitySupport.SUPPORTED, registry.supports("my-model", InputModality.FILE));
    }

    @Test
    void declaredVideoFalseWins() {
        var config = new GatewayModelConfig();
        config.supportsVideo = Boolean.FALSE;
        when(routingEngine.modelConfig("my-model")).thenReturn(config);

        assertEquals(ModalitySupport.UNSUPPORTED, registry.supports("my-model", InputModality.VIDEO));
    }
}
