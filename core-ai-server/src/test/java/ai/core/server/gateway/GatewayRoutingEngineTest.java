package ai.core.server.gateway;

import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @SuppressWarnings("unchecked")
    private GatewayRoutingEngine engine(List<GatewayModelConfig> models) {
        var engine = new GatewayRoutingEngine();
        engine.gatewayProviderCollection = (MongoCollection<GatewayProviderConfig>) mock(MongoCollection.class);
        engine.gatewayModelCollection = (MongoCollection<GatewayModelConfig>) mock(MongoCollection.class);
        when(engine.gatewayProviderCollection.find(any(Query.class))).thenReturn(List.of(provider()));
        when(engine.gatewayModelCollection.find(any(Query.class))).thenReturn(models);
        return engine;
    }

    private GatewayProviderConfig provider() {
        var provider = new GatewayProviderConfig();
        provider.id = "provider-1";
        provider.name = "provider-1";
        provider.type = "litellm";
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
