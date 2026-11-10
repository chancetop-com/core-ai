package ai.core.server.gateway;

import ai.core.server.domain.GatewayProviderConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class GatewayDecisionProbeTest {
    @Test
    void aDefaultDecisionModelAloneIdentifiesADecisionProvider() {
        var modelStore = mock(GatewayModelStore.class);
        when(modelStore.listByProviderId("provider-1")).thenReturn(List.of());

        var provider = new GatewayProviderConfig();
        provider.id = "provider-1";
        provider.defaultDecisionModel = "Decision-1";

        assertTrue(GatewayDecisionProbe.isDecisionProvider(provider, modelStore));
    }

    @Test
    void aPlainChatProviderIsNotADecisionProvider() {
        var modelStore = mock(GatewayModelStore.class);
        when(modelStore.listByProviderId("provider-1")).thenReturn(List.of());

        var provider = new GatewayProviderConfig();
        provider.id = "provider-1";

        assertFalse(GatewayDecisionProbe.isDecisionProvider(provider, modelStore));
    }
}
