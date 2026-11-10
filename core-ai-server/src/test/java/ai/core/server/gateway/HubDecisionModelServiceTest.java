package ai.core.server.gateway;

import ai.core.decision.DecisionModelHint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class HubDecisionModelServiceTest {
    @Test
    void mapsDecisionModelHintsInRoutingOrder() {
        var routing = mock(GatewayRoutingEngine.class);
        when(routing.decisionModelHints()).thenReturn(List.of(
                new DecisionModelHint("decision-1", "Microsoft Decision 1", "azure-uat", "Decision-1"),
                new DecisionModelHint("jev", null, "typesafe", "jev-latest")));
        var service = new HubDecisionModelService();
        service.gatewayRoutingEngine = routing;

        var response = service.models();

        assertThat(response.models).extracting(model -> model.modelId).containsExactly("decision-1", "jev");
        assertThat(response.models.getFirst().upstreamModel).isEqualTo("Decision-1");
        assertThat(response.models.getFirst().providerName).isEqualTo("azure-uat");
        assertThat(response.models.get(1).displayName).isNull();
    }
}
