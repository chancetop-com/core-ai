package ai.core.server.gateway;

import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayRoutingEngineTest {
    @Test
    void defaultChatModelIdPrefersMarkedDefault() {
        var engine = engine(List.of(model("a", false, 100), model("b", true, 200), model("c", false, 300)));

        assertEquals("b", engine.defaultChatModelId());
    }

    @Test
    void defaultChatModelIdFallsBackToHighestPriority() {
        var engine = engine(List.of(model("a", false, 100), model("b", false, 200)));

        assertEquals("a", engine.defaultChatModelId());
    }

    @Test
    void defaultChatModelIdIgnoresDisabledAndNonChatModels() {
        var disabled = model("disabled", true, 100);
        disabled.enabled = Boolean.FALSE;
        var videoOnly = model("video-only", false, 200);
        videoOnly.endpointTypes = List.of("video.generations");
        var engine = engine(List.of(disabled, videoOnly, model("fallback", false, 300)));

        assertEquals("fallback", engine.defaultChatModelId());
    }

    @Test
    void defaultChatModelIdReturnsNullWhenNoChatModel() {
        var videoOnly = model("video-only", false, 100);
        videoOnly.endpointTypes = List.of("video.generations");
        var engine = engine(List.of(videoOnly));

        assertNull(engine.defaultChatModelId());
    }

    @Test
    void routeResolvesPrefixedProviderModelFormat() {
        var engine = engine(List.of(model("deepseek-v4-flash", false, 100)), "deepseek/");

        var route = engine.route("deepseek/deepseek-v4-flash", GatewayEndpointType.CHAT_COMPLETIONS);

        assertEquals("deepseek-v4-flash", route.upstreamModel());
        assertEquals("provider-1", route.provider().name);
    }

    @Test
    void routePrefersExactModelIdOverPrefixedFormat() {
        var exact = model("deepseek/deepseek-v4-flash", false, 100);
        exact.upstreamModel = "exact-upstream";
        var engine = engine(List.of(exact, model("deepseek-v4-flash", false, 200)), "deepseek/");

        var route = engine.route("deepseek/deepseek-v4-flash", GatewayEndpointType.CHAT_COMPLETIONS);

        assertEquals("exact-upstream", route.upstreamModel());
    }

    @Test
    void routePrefixedFormatWithWrongProviderPrefixIsRejected() {
        var engine = engine(List.of(model("deepseek-v4-flash", false, 100)), "deepseek/");

        assertThrows(BadRequestException.class,
            () -> engine.route("azure/deepseek-v4-flash", GatewayEndpointType.CHAT_COMPLETIONS));
    }

    @Test
    void routePrefixedFormatRespectsEndpointSupport() {
        var videoOnly = model("deepseek-v4-flash", false, 100);
        videoOnly.endpointTypes = List.of("video.generations");
        var engine = engine(List.of(videoOnly), "deepseek/");

        assertThrows(BadRequestException.class,
            () -> engine.route("deepseek/deepseek-v4-flash", GatewayEndpointType.CHAT_COMPLETIONS));
    }

    @Test
    void modelConfigAcceptsPrefixedProviderModelFormat() {
        var engine = engine(List.of(model("deepseek-v4-flash", false, 100)), "deepseek/");

        assertEquals("deepseek-v4-flash", engine.modelConfig("deepseek/deepseek-v4-flash").modelId);
        assertNull(engine.modelConfig("deepseek/other-model"));
        assertNull(engine.modelConfig("azure/deepseek-v4-flash"));
    }

    @SuppressWarnings("unchecked")
    private GatewayRoutingEngine engine(List<GatewayModelConfig> models) {
        return engine(models, null);
    }

    @SuppressWarnings("unchecked")
    private GatewayRoutingEngine engine(List<GatewayModelConfig> models, String modelPrefix) {
        var engine = new GatewayRoutingEngine();
        engine.gatewayProviderCollection = (MongoCollection<GatewayProviderConfig>) mock(MongoCollection.class);
        engine.gatewayModelCollection = (MongoCollection<GatewayModelConfig>) mock(MongoCollection.class);
        when(engine.gatewayProviderCollection.find(any(Query.class))).thenReturn(List.of(provider(modelPrefix)));
        when(engine.gatewayModelCollection.find(any(Query.class))).thenReturn(models);
        return engine;
    }

    private GatewayProviderConfig provider(String modelPrefix) {
        var provider = new GatewayProviderConfig();
        provider.id = "provider-1";
        provider.name = "provider-1";
        provider.type = "litellm";
        provider.modelPrefix = modelPrefix;
        provider.enabled = Boolean.TRUE;
        return provider;
    }

    private GatewayModelConfig model(String modelId, boolean isDefault, long priority) {
        var model = new GatewayModelConfig();
        model.id = modelId;
        model.modelId = modelId;
        model.providerId = "provider-1";
        model.upstreamModel = modelId;
        model.endpointTypes = List.of("chat.completions");
        model.enabled = Boolean.TRUE;
        model.isDefault = isDefault;
        model.priority = priority;
        return model;
    }
}
