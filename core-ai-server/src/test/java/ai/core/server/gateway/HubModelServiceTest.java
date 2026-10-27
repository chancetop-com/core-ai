package ai.core.server.gateway;

import ai.core.api.server.gateway.GatewayAvailableModelView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HubModelServiceTest {
    @Test
    void listsResponsesOnlyModelsAsChatModels() {
        var engine = mock(GatewayRoutingEngine.class);
        when(engine.availableModels()).thenReturn(List.of(
                view("gpt-6.1-sol", List.of("responses")),
                view("image-model", List.of("image.generations")),
                view("chat-model", List.of("chat.completions"))));
        var service = new HubModelService();
        service.gatewayRoutingEngine = engine;

        var response = service.models("user-1");

        assertEquals(List.of("gpt-6.1-sol", "chat-model"),
                response.models.stream().map(model -> model.modelId).toList());
    }

    private GatewayAvailableModelView view(String modelId, List<String> endpointTypes) {
        var view = new GatewayAvailableModelView();
        view.modelId = modelId;
        view.endpointTypes = endpointTypes;
        return view;
    }
}
