package ai.core.server.gateway;

import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.domain.User;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayProviderServiceTest {
    @Test
    void createMasksApiKeyAndAppliesDefaults() {
        var service = serviceWithUser(admin("admin-1"));
        var request = new GatewayProviderRequest();
        request.name = "OpenAI";
        request.type = "openai";
        request.baseUrl = "https://api.openai.com/v1/";
        request.apiKey = "sk-test-123456";

        var view = service.create(request, "admin-1");

        assertEquals("OpenAI", view.name);
        assertEquals("openai", view.type);
        assertEquals("https://api.openai.com/v1", view.baseUrl);
        assertEquals("openai/", view.modelPrefix);
        assertEquals("sk-t...3456", view.apiKeyMasked);
        assertTrue(view.hasApiKey);

        var captor = ArgumentCaptor.forClass(GatewayProviderConfig.class);
        verify(service.gatewayProviderCollection).insert(captor.capture());
        assertNull(captor.getValue().apiKey);
        assertTrue(service.secretProtector.isProtected(captor.getValue().apiKeyEncrypted));
        assertEquals("sk-test-123456", service.secretProtector.unprotect(captor.getValue().apiKeyEncrypted));
    }

    @Test
    void updateKeepsExistingApiKeyWhenBlank() {
        var service = serviceWithUser(admin("admin-1"));
        var existing = new GatewayProviderConfig();
        existing.id = "provider-1";
        existing.name = "DeepSeek";
        existing.type = "deepseek";
        existing.baseUrl = "https://api.deepseek.com/v1";
        existing.apiKey = "old-secret";
        existing.enabled = true;
        when(service.gatewayProviderCollection.get("provider-1")).thenReturn(Optional.of(existing));

        var request = new GatewayProviderRequest();
        request.name = "DeepSeek Updated";
        request.apiKey = "";

        service.update("provider-1", request, "admin-1");

        var captor = ArgumentCaptor.forClass(GatewayProviderConfig.class);
        verify(service.gatewayProviderCollection).replace(captor.capture());
        assertEquals("DeepSeek Updated", captor.getValue().name);
        assertEquals("old-secret", captor.getValue().apiKey);
    }

    @Test
    void rejectsInvalidExtraBodyJson() {
        var service = serviceWithUser(admin("admin-1"));
        var request = new GatewayProviderRequest();
        request.name = "OpenAI";
        request.type = "openai";
        request.baseUrl = "https://api.openai.com/v1";
        request.requestExtraBody = "[1, 2]";

        assertThrows(BadRequestException.class, () -> service.create(request, "admin-1"));
    }

    @Test
    void blocksPrivateNetworkUnlessAllowed() {
        assertThrows(BadRequestException.class, () -> GatewayNetworkGuard.validateOutboundUrl("http://127.0.0.1:4000/models", false));
        GatewayNetworkGuard.validateOutboundUrl("http://127.0.0.1:4000/models", true);
    }

    @Test
    void rejectsNonAdmin() {
        var service = serviceWithUser(user("user-1"));
        var request = new GatewayProviderRequest();
        request.name = "OpenAI";
        request.type = "openai";
        request.baseUrl = "https://api.openai.com/v1";

        assertThrows(ForbiddenException.class, () -> service.create(request, "user-1"));
    }

    @SuppressWarnings("unchecked")
    private GatewayProviderService serviceWithUser(User user) {
        var service = new GatewayProviderService();
        service.gatewayProviderCollection = (MongoCollection<GatewayProviderConfig>) mock(MongoCollection.class);
        service.userCollection = (MongoCollection<User>) mock(MongoCollection.class);
        service.secretProtector = new GatewaySecretProtector("test-secret");
        when(service.userCollection.get(user.id)).thenReturn(Optional.of(user));
        return service;
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
