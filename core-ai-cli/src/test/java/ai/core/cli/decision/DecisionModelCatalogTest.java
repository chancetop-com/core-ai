package ai.core.cli.decision;

import ai.core.api.server.hubmodels.HubDecisionModelView;
import ai.core.api.server.hubmodels.ListHubDecisionModelsResponse;
import ai.core.cli.hub.model.HubDecisionModelClient;
import ai.core.cli.http.RemoteApiException;
import ai.core.decision.DecisionModelHint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class DecisionModelCatalogTest {
    @Test
    void loadsModelsInPriorityOrder() {
        var client = mock(HubDecisionModelClient.class);
        when(client.models()).thenReturn(response());
        var catalog = new DecisionModelCatalog(client);

        catalog.ensureLoaded(1_000);

        assertEquals(List.of("decision-1", "jev"), ids(catalog.models()));
        assertEquals("decision-1", catalog.defaultModel());
        assertEquals("Decision-1", catalog.models().getFirst().upstreamModel());
        assertEquals("azure-uat", catalog.models().getFirst().providerName());
    }

    @Test
    void doesNotRefetchWhileTheSnapshotIsFresh() {
        var client = mock(HubDecisionModelClient.class);
        when(client.models()).thenReturn(response());
        var catalog = new DecisionModelCatalog(client);

        catalog.ensureLoaded(1_000);
        catalog.ensureLoaded(1_000);

        verify(client, times(1)).models();
    }

    @Test
    void aFailureKeepsAnEmptyListAndNeverThrows() {
        var client = mock(HubDecisionModelClient.class);
        when(client.models()).thenThrow(new RemoteApiException(500, "server down"));
        var catalog = new DecisionModelCatalog(client);

        catalog.ensureLoaded(1_000);

        assertTrue(catalog.models().isEmpty());
        assertEquals(null, catalog.defaultModel());
    }

    private ListHubDecisionModelsResponse response() {
        var response = new ListHubDecisionModelsResponse();
        response.models = List.of(
                model("decision-1", "Decision-1", "azure-uat", "Microsoft Decision 1"),
                model("jev", "jev-latest", "typesafe", null));
        return response;
    }

    private HubDecisionModelView model(String modelId, String upstreamModel, String providerName, String displayName) {
        var view = new HubDecisionModelView();
        view.modelId = modelId;
        view.upstreamModel = upstreamModel;
        view.providerName = providerName;
        view.displayName = displayName;
        return view;
    }

    private List<String> ids(List<DecisionModelHint> models) {
        return models.stream().map(DecisionModelHint::modelId).toList();
    }
}
