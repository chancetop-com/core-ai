package ai.core.server.gateway;

import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.domain.User;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayModelServiceTest {
    @Test
    void createAppliesDefaultsAndNormalizesEndpointTypes() {
        var service = serviceWithUser(admin("admin-1"), provider("provider-1", "LiteLLM"));
        var request = new GatewayModelRequest();
        request.modelId = "fast-chat";
        request.providerId = "provider-1";
        request.upstreamModel = "deepseek/deepseek-v4-flash";
        request.endpointTypes = List.of("chat", "responses", "chat.completions");

        var view = service.create(request, "admin-1");

        assertEquals("fast-chat", view.modelId);
        assertEquals("LiteLLM", view.providerName);
        assertEquals(List.of("chat.completions", "responses"), view.endpointTypes);
        assertEquals(100L, view.priority);

        var captor = ArgumentCaptor.forClass(GatewayModelConfig.class);
        verify(service.gatewayModelCollection).insert(captor.capture());
        assertEquals(Boolean.TRUE, captor.getValue().enabled);
    }

    @Test
    void rejectsMissingProvider() {
        var service = serviceWithUser(admin("admin-1"), null);
        var request = new GatewayModelRequest();
        request.modelId = "fast-chat";
        request.providerId = "missing";
        request.upstreamModel = "gpt-4o";

        assertThrows(BadRequestException.class, () -> service.create(request, "admin-1"));
    }

    @Test
    void rejectsInvalidEndpointType() {
        var service = serviceWithUser(admin("admin-1"), provider("provider-1", "LiteLLM"));
        var request = new GatewayModelRequest();
        request.modelId = "fast-chat";
        request.providerId = "provider-1";
        request.upstreamModel = "gpt-4o";
        request.endpointTypes = List.of("images");

        assertThrows(BadRequestException.class, () -> service.create(request, "admin-1"));
    }

    @Test
    void updateClearsNullableNumericFields() {
        var service = serviceWithUser(admin("admin-1"), provider("provider-1", "LiteLLM"));
        var existing = new GatewayModelConfig();
        existing.id = "model-1";
        existing.modelId = "fast-chat";
        existing.providerId = "provider-1";
        existing.upstreamModel = "gpt-4o";
        existing.endpointTypes = List.of("chat.completions");
        existing.enabled = true;
        existing.priority = 10L;
        existing.contextWindow = 128_000L;
        existing.inputPricePer1MTokens = 1.25D;
        existing.outputPricePer1MTokens = 5D;
        when(service.gatewayModelCollection.get("model-1")).thenReturn(Optional.of(existing));

        var request = new GatewayModelRequest();
        request.fields = Set.of("priority", "contextWindow", "inputPricePer1MTokens", "outputPricePer1MTokens");

        service.update("model-1", request, "admin-1");

        var captor = ArgumentCaptor.forClass(GatewayModelConfig.class);
        verify(service.gatewayModelCollection).replace(captor.capture());
        assertEquals(100L, captor.getValue().priority);
        assertNull(captor.getValue().contextWindow);
        assertNull(captor.getValue().inputPricePer1MTokens);
        assertNull(captor.getValue().outputPricePer1MTokens);
    }

    @Test
    void importUsesOfficialModelIdAsDefaultAlias() {
        var service = serviceWithUser(admin("admin-1"), provider("provider-1", "LiteLLM"));
        service.gatewayModelDiscoveryService = new GatewayModelDiscoveryService() {
            @Override
            List<GatewayModelMetadata> discover(GatewayProviderConfig provider) {
                return List.of(new GatewayModelMetadata(
                        "deepseek/deepseek-chat",
                        "DeepSeek Chat",
                        List.of("chat.completions"),
                        64_000L,
                        true,
                        true,
                        false,
                        1D,
                        2D
                ));
            }
        };
        when(service.gatewayModelCollection.find(any(Query.class))).thenReturn(List.of());
        var request = new ImportGatewayModelsRequest();
        var model = new ImportGatewayModelsRequest.Model();
        model.upstreamModel = "deepseek/deepseek-chat";
        request.models = List.of(model);

        var response = service.importModels("provider-1", request, "admin-1");

        assertEquals(1, response.models.size());
        assertEquals("deepseek/deepseek-chat", response.models.get(0).modelId);
        assertEquals("deepseek/deepseek-chat", response.models.get(0).upstreamModel);
        assertEquals(List.of("chat.completions"), response.models.get(0).endpointTypes);
        assertTrue(response.models.get(0).supportsStream);
        assertTrue(response.models.get(0).supportsTools);
        assertEquals(64_000L, response.models.get(0).contextWindow);
        assertEquals(1D, response.models.get(0).inputPricePer1MTokens);

        var captor = ArgumentCaptor.forClass(GatewayModelConfig.class);
        verify(service.gatewayModelCollection).insert(captor.capture());
        assertEquals("deepseek/deepseek-chat", captor.getValue().modelId);
    }

    @Test
    void rejectsNonAdmin() {
        var service = serviceWithUser(user("user-1"), provider("provider-1", "LiteLLM"));
        var request = new GatewayModelRequest();
        request.modelId = "fast-chat";
        request.providerId = "provider-1";
        request.upstreamModel = "gpt-4o";

        assertThrows(ForbiddenException.class, () -> service.create(request, "user-1"));
    }

    @SuppressWarnings("unchecked")
    private GatewayModelService serviceWithUser(User user, GatewayProviderConfig provider) {
        var service = new GatewayModelService();
        service.gatewayModelCollection = (MongoCollection<GatewayModelConfig>) mock(MongoCollection.class);
        service.gatewayProviderCollection = (MongoCollection<GatewayProviderConfig>) mock(MongoCollection.class);
        service.userCollection = (MongoCollection<User>) mock(MongoCollection.class);
        when(service.userCollection.get(user.id)).thenReturn(Optional.of(user));
        if (provider != null) {
            when(service.gatewayProviderCollection.get(provider.id)).thenReturn(Optional.of(provider));
            when(service.gatewayProviderCollection.find(any(Query.class))).thenReturn(List.of(provider));
        } else {
            when(service.gatewayProviderCollection.get("missing")).thenReturn(Optional.empty());
            when(service.gatewayProviderCollection.find(any(Query.class))).thenReturn(List.of());
        }
        return service;
    }

    private GatewayProviderConfig provider(String id, String name) {
        var provider = new GatewayProviderConfig();
        provider.id = id;
        provider.name = name;
        provider.enabled = true;
        return provider;
    }

    private User admin(String id) {
        var user = new User();
        user.id = id;
        user.role = "admin";
        return user;
    }

    private User user(String id) {
        var user = new User();
        user.id = id;
        user.role = "user";
        return user;
    }
}
