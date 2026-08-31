package ai.core.server.render;

import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.gateway.GatewayEndpointType;
import ai.core.server.gateway.GatewayRoutingEngine;
import ai.core.tool.tools.MediaModelHint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * One registry, not two: capabilities are read off the admin-editable gateway model row, layered on
 * the code-level family defaults, and the row's updatedAt is what invalidates render cache keys.
 *
 * @author stephen
 */
class RenderCapsResolverTest {
    private static GatewayModelConfig config(String modelId, String upstream, Integer maxImages) {
        var config = new GatewayModelConfig();
        config.modelId = modelId;
        config.upstreamModel = upstream;
        config.maxImageReferences = maxImages;
        config.updatedAt = ZonedDateTime.parse("2026-08-31T00:00:00Z");
        return config;
    }

    private RenderCapsResolver resolver;
    private GatewayRoutingEngine routingEngine;

    @BeforeEach
    void createResolver() {
        resolver = new RenderCapsResolver();
        routingEngine = mock(GatewayRoutingEngine.class);
        resolver.routingEngine = routingEngine;
    }

    @Test
    void adminRowOverlaysTheFamilyDefaults() {
        var config = config("seedance-2.5", "bytedance/seedance-2", 30);
        config.nativeAudio = Boolean.TRUE;
        config.maxOutputDurationSec = 30.0;
        when(routingEngine.modelConfig("seedance-2.5")).thenReturn(config);

        var caps = resolver.resolve("seedance-2.5");

        assertEquals(30, caps.capabilities().maxImages(), "the admin row overrides the family default of 4");
        assertEquals(2, caps.capabilities().maxVideos(), "an unset field keeps the family default");
        assertTrue(caps.nativeAudio());
        assertEquals(30.0, caps.maxOutputDurationSec());
        assertEquals("2026-08-31T00:00Z", caps.version(), "the row's updatedAt is the cache-key marker");
    }

    @Test
    void unknownModelResolvesToNull() {
        when(routingEngine.modelConfig("made-up-model")).thenReturn(null);

        assertNull(resolver.resolve("made-up-model"));
        assertNull(resolver.resolve(" "), "a blank id must not reach the gateway");
    }

    @Test
    void carriesImageReferencesReportsWhatAReferenceLimitOfZeroMeans() {
        when(routingEngine.modelConfig("text-only")).thenReturn(config("text-only", "minimax-h3/text-to-video", 0));
        when(routingEngine.modelConfig("unconstrained")).thenReturn(config("unconstrained", "brand-new-model", null));

        assertFalse(resolver.resolve("text-only").carriesImageReferences());
        assertTrue(resolver.resolve("unconstrained").carriesImageReferences(), "an unknown model is unconstrained, not zero");
    }

    @Test
    void videoModelsSkipsAliasesWithoutAConfigRow() {
        when(routingEngine.mediaModelHints(GatewayEndpointType.VIDEO_GENERATION)).thenReturn(List.of(
            new MediaModelHint("seedance-2.5", "bytedance/seedance-2", "kie"),
            new MediaModelHint("stale-alias", "whatever", "kie")));
        when(routingEngine.modelConfig("seedance-2.5")).thenReturn(config("seedance-2.5", "bytedance/seedance-2", null));
        when(routingEngine.modelConfig("stale-alias")).thenReturn(null);

        var models = resolver.videoModels();

        assertEquals(1, models.size());
        assertEquals("seedance-2.5", models.get(0).model());
    }
}
