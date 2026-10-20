package ai.core.cli.media;

import ai.core.api.server.hubmodels.HubMediaModelView;
import ai.core.api.server.hubmodels.ListHubMediaModelsResponse;
import ai.core.cli.http.RemoteApiException;
import ai.core.tool.tools.MediaModelHint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class MediaModelCatalogTest {
    @Test
    void loadsEndpointListsAndKeepsTheirOrder() {
        var client = mock(HubMediaModelClient.class);
        when(client.models()).thenReturn(response());
        var catalog = new MediaModelCatalog(client);

        catalog.ensureLoaded(1_000);

        assertEquals(List.of("gpt-image-2.5", "seedream-5"), ids(catalog.imageModels()));
        assertEquals(List.of("gpt-image-2.5-edit"), ids(catalog.imageEditModels()));
        assertEquals(List.of("seedance-2.5"), ids(catalog.videoModels()));
        assertEquals("gpt-image-2.5", catalog.defaultImageModel());
        assertEquals("gpt-image-2.5-edit", catalog.defaultImageEditModel());
        assertEquals("seedance-2.5", catalog.defaultVideoModel());
        assertEquals("GPT Image 2.5", catalog.imageModels().getFirst().displayName());
        assertNull(catalog.error());
        assertFalse(catalog.unavailable());
    }

    @Test
    void concurrentLoadsJoinTheSameRefresh() throws Exception {
        var client = mock(HubMediaModelClient.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(client.models()).thenAnswer(invocation -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return response();
        });
        var catalog = new MediaModelCatalog(client);

        var first = CompletableFuture.runAsync(() -> catalog.ensureLoaded(2_000));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var second = CompletableFuture.runAsync(() -> catalog.ensureLoaded(2_000));
        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);

        verify(client, times(1)).models();
    }

    @Test
    void doesNotRefetchWhileTheSnapshotIsFresh() {
        var client = mock(HubMediaModelClient.class);
        when(client.models()).thenReturn(response());
        var catalog = new MediaModelCatalog(client);

        catalog.ensureLoaded(1_000);
        catalog.ensureLoaded(1_000);

        verify(client, times(1)).models();
    }

    @Test
    void keepsLastKnownGoodListsWhenARefreshFails() {
        var client = mock(HubMediaModelClient.class);
        when(client.models()).thenReturn(response());
        var catalog = new MediaModelCatalog(client, 0, 0);
        catalog.refresh();

        when(client.models()).thenThrow(new RemoteApiException(500, "server down"));
        catalog.refresh();

        assertEquals(List.of("gpt-image-2.5", "seedream-5"), ids(catalog.imageModels()));
        assertTrue(catalog.error().contains("HTTP 500"), catalog.error());
        assertFalse(catalog.unavailable());
    }

    @Test
    void marksOlderServersWithoutTheCatalogEndpoint() {
        var client = mock(HubMediaModelClient.class);
        when(client.models()).thenThrow(new RemoteApiException(404, "not found"));
        var catalog = new MediaModelCatalog(client);

        catalog.ensureLoaded(1_000);

        assertTrue(catalog.unavailable());
        assertTrue(catalog.imageModels().isEmpty());
        assertNull(catalog.defaultImageModel());
    }

    @Test
    void reportsAnExpiredLoginWithANextStep() {
        var client = mock(HubMediaModelClient.class);
        when(client.models()).thenThrow(new RemoteApiException(401, "unauthorized"));
        var catalog = new MediaModelCatalog(client);

        catalog.ensureLoaded(1_000);

        assertEquals("Authentication failed. Sign in again from Hub.", catalog.error());
    }

    @Test
    void aNetworkFailureIsReportedButNeverThrown() {
        var client = mock(HubMediaModelClient.class);
        when(client.models()).thenThrow(new IllegalStateException("connection refused"));
        var catalog = new MediaModelCatalog(client);

        catalog.ensureLoaded(1_000);

        assertTrue(catalog.error().contains("connection refused"), catalog.error());
        assertTrue(catalog.imageModels().isEmpty());
    }

    private ListHubMediaModelsResponse response() {
        var response = new ListHubMediaModelsResponse();
        response.imageModels = List.of(
                model("gpt-image-2.5", "gpt-image-2.5-2026", "azure-uat", "GPT Image 2.5"),
                model("seedream-5", "seedream/5-pro", "kie", "Seedream 5"));
        response.imageEditModels = List.of(model("gpt-image-2.5-edit", "gpt-image-2.5-2026", "azure-uat", "GPT Image 2.5 Edit"));
        response.videoModels = List.of(model("seedance-2.5", "doubao-seedance-2-5", "volcengine", "Seedance 2.5"));
        return response;
    }

    private HubMediaModelView model(String modelId, String upstreamModel, String providerName, String displayName) {
        var view = new HubMediaModelView();
        view.modelId = modelId;
        view.upstreamModel = upstreamModel;
        view.providerName = providerName;
        view.displayName = displayName;
        return view;
    }

    private List<String> ids(List<MediaModelHint> models) {
        return models.stream().map(MediaModelHint::modelId).toList();
    }
}
