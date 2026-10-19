package ai.core.server.gateway;

import ai.core.tool.tools.MediaModelHint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class HubMediaModelServiceTest {
    @Test
    void mapsEachEndpointSeparatelyAndKeepsPriorityOrder() {
        var routing = mock(GatewayRoutingEngine.class);
        when(routing.mediaModelHints(GatewayEndpointType.IMAGE_GENERATION)).thenReturn(List.of(
                new MediaModelHint("gpt-image-2.5", "gpt-image-2.5", "azure-uat", "GPT Image 2.5"),
                new MediaModelHint("seedream-5", "seedream/5-pro", "kie", "Seedream 5")));
        when(routing.mediaModelHints(GatewayEndpointType.IMAGE_EDIT)).thenReturn(List.of(
                new MediaModelHint("gpt-image-2.5", "gpt-image-2.5", "azure-uat", "GPT Image 2.5"),
                new MediaModelHint("seedream-5-edit", "seedream/5-pro-image-to-image", "kie", "Seedream 5 Edit")));
        when(routing.mediaModelHints(GatewayEndpointType.VIDEO_GENERATION)).thenReturn(List.of(
                new MediaModelHint("seedance-2.5", "doubao-seedance-2-5", "volcengine", "Seedance 2.5")));
        var service = new HubMediaModelService();
        service.gatewayRoutingEngine = routing;

        var response = service.models();

        assertThat(response.imageModels).extracting(model -> model.modelId)
                .containsExactly("gpt-image-2.5", "seedream-5");
        assertThat(response.imageEditModels).extracting(model -> model.modelId)
                .containsExactly("gpt-image-2.5", "seedream-5-edit");
        assertThat(response.videoModels).extracting(model -> model.modelId)
                .containsExactly("seedance-2.5");
        assertThat(response.imageModels.getFirst().displayName).isEqualTo("GPT Image 2.5");
        assertThat(response.imageModels.getFirst().upstreamModel).isEqualTo("gpt-image-2.5");
        assertThat(response.imageModels.getFirst().providerName).isEqualTo("azure-uat");
    }

    @Test
    void dedupesFailoverRegistrationsInsideOneEndpoint() {
        var routing = mock(GatewayRoutingEngine.class);
        when(routing.mediaModelHints(GatewayEndpointType.IMAGE_GENERATION)).thenReturn(List.of(
                new MediaModelHint("gpt-image-2.5", "gpt-image-2.5", "azure-uat", "GPT Image 2.5"),
                new MediaModelHint("gpt-image-2.5", "gpt-image-2.5", "azure-backup", "GPT Image 2.5"),
                new MediaModelHint("seedream-5", "seedream/5-pro", "kie", null)));
        var service = new HubMediaModelService();
        service.gatewayRoutingEngine = routing;

        var response = service.models();

        assertThat(response.imageModels).extracting(model -> model.modelId)
                .containsExactly("gpt-image-2.5", "seedream-5");
        assertThat(response.imageModels.getFirst().providerName).isEqualTo("azure-uat");
        assertThat(response.imageModels.get(1).displayName).isNull();
        assertThat(response.videoModels).isEmpty();
    }
}
