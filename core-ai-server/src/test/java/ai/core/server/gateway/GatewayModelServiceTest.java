package ai.core.server.gateway;

import ai.core.api.server.gateway.GatewayModelRequest;
import ai.core.api.server.gateway.ImportGatewayModelsRequest;
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
import java.util.Map;
import java.util.Optional;

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
        existing.enabled = Boolean.TRUE;
        existing.priority = 10L;
        existing.contextWindow = 128_000L;
        existing.inputPricePer1MTokens = 1.25D;
        existing.outputPricePer1MTokens = 5D;
        when(service.gatewayModelCollection.get("model-1")).thenReturn(Optional.of(existing));

        var request = new GatewayModelRequest();
        request.fields = Map.of("priority", Boolean.TRUE, "contextWindow", Boolean.TRUE, "inputPricePer1MTokens", Boolean.TRUE, "outputPricePer1MTokens", Boolean.TRUE);

        service.update("model-1", request, "admin-1");

        var captor = ArgumentCaptor.forClass(GatewayModelConfig.class);
        verify(service.gatewayModelCollection).replace(captor.capture());
        assertEquals(100L, captor.getValue().priority);
        assertNull(captor.getValue().contextWindow);
        assertNull(captor.getValue().inputPricePer1MTokens);
        assertNull(captor.getValue().outputPricePer1MTokens);
    }

    @Test
    void createAppliesResponseFormatNormalization() {
        var service = serviceWithUser(admin("admin-1"), provider("provider-1", "LiteLLM"));
        var request = new GatewayModelRequest();
        request.modelId = "deepseek-chat";
        request.providerId = "provider-1";
        request.upstreamModel = "deepseek/deepseek-chat";
        request.responseFormat = "JSON_OBJECT";

        var view = service.create(request, "admin-1");

        assertEquals("json_object", view.responseFormat);
        var captor = ArgumentCaptor.forClass(GatewayModelConfig.class);
        verify(service.gatewayModelCollection).insert(captor.capture());
        assertEquals("json_object", captor.getValue().responseFormat);
    }

    @Test
    void rejectsInvalidResponseFormat() {
        var service = serviceWithUser(admin("admin-1"), provider("provider-1", "LiteLLM"));
        var request = new GatewayModelRequest();
        request.modelId = "fast-chat";
        request.providerId = "provider-1";
        request.upstreamModel = "gpt-4o";
        request.responseFormat = "yaml";

        assertThrows(BadRequestException.class, () -> service.create(request, "admin-1"));
    }

    @Test
    void updateClearsResponseFormat() {
        var service = serviceWithUser(admin("admin-1"), provider("provider-1", "LiteLLM"));
        var existing = new GatewayModelConfig();
        existing.id = "model-1";
        existing.modelId = "fast-chat";
        existing.providerId = "provider-1";
        existing.upstreamModel = "deepseek/deepseek-chat";
        existing.endpointTypes = List.of("chat.completions");
        existing.enabled = Boolean.TRUE;
        existing.responseFormat = "json_object";
        when(service.gatewayModelCollection.get("model-1")).thenReturn(Optional.of(existing));

        var request = new GatewayModelRequest();
        request.fields = Map.of("responseFormat", Boolean.TRUE);

        service.update("model-1", request, "admin-1");

        var captor = ArgumentCaptor.forClass(GatewayModelConfig.class);
        verify(service.gatewayModelCollection).replace(captor.capture());
        assertNull(captor.getValue().responseFormat);
    }

    @Test
    void importUsesOfficialModelIdAsDefaultAlias() {
        var service = serviceWithUser(admin("admin-1"), provider("provider-1", "LiteLLM"));
        service.gatewayModelDiscoveryService = new StubModelDiscoveryService();
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

    @Test
    void rejectsGeminiResponsesEndpoint() {
        var service = serviceWithUser(admin("admin-1"), vertexGeminiProvider());
        var request = new GatewayModelRequest();
        request.modelId = "gemini-resp";
        request.providerId = "google";
        request.upstreamModel = "gemini-3.8-flash";
        request.endpointTypes = List.of("responses");

        var exception = assertThrows(BadRequestException.class, () -> service.create(request, "admin-1"));
        assertTrue(exception.getMessage().contains("responses"));
    }

    @Test
    void rejectsVertexGeminiChatWithoutProjectId() {
        var provider = vertexGeminiProvider();
        provider.vertexProjectId = null;
        var service = serviceWithUser(admin("admin-1"), provider);
        var request = new GatewayModelRequest();
        request.modelId = "gemini-chat";
        request.providerId = "google";
        request.upstreamModel = "gemini-3.8-flash";

        assertThrows(BadRequestException.class, () -> service.create(request, "admin-1"));
    }

    @Test
    void rejectsVertexGeminiChatWithoutCredentials() {
        var provider = vertexGeminiProvider();
        provider.googleCredentialsEncrypted = null;
        var service = serviceWithUser(admin("admin-1"), provider);
        var request = new GatewayModelRequest();
        request.modelId = "gemini-chat";
        request.providerId = "google";
        request.upstreamModel = "gemini-3.8-flash";

        assertThrows(BadRequestException.class, () -> service.create(request, "admin-1"));
    }

    @Test
    void rejectsDeveloperGeminiChatWithoutApiKey() {
        var provider = new GatewayProviderConfig();
        provider.id = "google-dev";
        provider.name = "google-dev";
        provider.type = "gemini";
        provider.baseUrl = "https://generativelanguage.googleapis.com/v1beta";
        provider.enabled = Boolean.TRUE;
        var service = serviceWithUser(admin("admin-1"), provider);
        var request = new GatewayModelRequest();
        request.modelId = "gemini-chat";
        request.providerId = "google-dev";
        request.upstreamModel = "gemini-3.8-flash";

        assertThrows(BadRequestException.class, () -> service.create(request, "admin-1"));
    }

    @Test
    void acceptsVertexGeminiChatWithFullCredentials() {
        var service = serviceWithUser(admin("admin-1"), vertexGeminiProvider());
        var request = new GatewayModelRequest();
        request.modelId = "gemini-chat";
        request.providerId = "google";
        request.upstreamModel = "gemini-3.8-flash";

        var view = service.create(request, "admin-1");

        assertEquals("gemini-chat", view.modelId);
    }

    @Test
    void ignoresMediaEndpointsForGeminiProvider() {
        var provider = new GatewayProviderConfig();
        provider.id = "google-dev";
        provider.name = "google-dev";
        provider.type = "gemini";
        provider.baseUrl = "https://generativelanguage.googleapis.com/v1beta";
        provider.enabled = Boolean.TRUE;
        var service = serviceWithUser(admin("admin-1"), provider);
        var request = new GatewayModelRequest();
        request.modelId = "gemini-video";
        request.providerId = "google-dev";
        request.upstreamModel = "gemini-3.8-flash";
        request.endpointTypes = List.of("video.generations");

        var view = service.create(request, "admin-1");

        assertEquals("gemini-video", view.modelId);
    }

    private GatewayProviderConfig vertexGeminiProvider() {
        var provider = new GatewayProviderConfig();
        provider.id = "google";
        provider.name = "google";
        provider.type = "gemini";
        provider.baseUrl = "https://aiplatform.googleapis.com/v1beta1";
        provider.enabled = Boolean.TRUE;
        provider.vertexProjectId = "my-project";
        provider.vertexLocation = "us-central1";
        provider.googleCredentialsEncrypted = "enc:v1:credentials";
        return provider;
    }

    @SuppressWarnings("unchecked")
    private GatewayModelService serviceWithUser(User user, GatewayProviderConfig provider) {
        var service = new GatewayModelService();
        service.gatewayModelCollection = (MongoCollection<GatewayModelConfig>) mock(MongoCollection.class);
        service.gatewayProviderCollection = (MongoCollection<GatewayProviderConfig>) mock(MongoCollection.class);
        service.userCollection = (MongoCollection<User>) mock(MongoCollection.class);
        when(service.userCollection.get(user.id)).thenReturn(Optional.of(user));
        when(service.gatewayModelCollection.find(any(Query.class))).thenReturn(List.of());
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
        provider.enabled = Boolean.TRUE;
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

    private static final class StubModelDiscoveryService extends GatewayModelDiscoveryService {
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
                    null,
                    1D,
                    2D
            ));
        }
    }
}
