package ai.core.server.render;

import ai.core.media.reference.MediaModelCapabilities;
import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.gateway.GatewayEndpointType;
import ai.core.server.gateway.GatewayReferenceCompiler;
import ai.core.server.gateway.GatewayRoutingEngine;
import core.framework.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves what a media model can actually do from its admin-maintained gateway row: reference
 * limits per modality, addressing syntax, audio-reference support, plus the video-render facts
 * (native audio, longest output). Code-level family defaults come first and the row overlays them,
 * so a new model stays one admin row.
 * <p>
 * There is deliberately no second registry and no tool to write one: a pipeline that kept its own
 * copy could describe the same model two ways, and re-registering would silently change render
 * cache keys.
 *
 * @author stephen
 */
public class RenderCapsResolver {
    @Inject
    GatewayRoutingEngine routingEngine;

    /** Null when the id is not a configured gateway model. */
    public RenderCaps resolve(String model) {
        var config = model == null || model.isBlank() ? null : routingEngine.modelConfig(model);
        if (config == null) return null;
        return new RenderCaps(model, GatewayReferenceCompiler.capabilities(config.upstreamModel, config),
            Boolean.TRUE.equals(config.nativeAudio), config.maxOutputDurationSec, version(config));
    }

    public List<RenderCaps> videoModels() {
        return modelsOf(GatewayEndpointType.VIDEO_GENERATION);
    }

    public List<RenderCaps> imageModels() {
        return modelsOf(GatewayEndpointType.IMAGE_GENERATION);
    }

    private List<RenderCaps> modelsOf(GatewayEndpointType endpoint) {
        var result = new ArrayList<RenderCaps>();
        for (var hint : routingEngine.mediaModelHints(endpoint)) {
            var caps = resolve(hint.modelId());
            if (caps != null) result.add(caps);
        }
        return result;
    }

    /**
     * Belongs in render cache keys: a capability change alters which references are actually sent and
     * how the prompt addresses them, so downstream products must not keep matching. Models without an
     * admin override ride the code-level defaults, which only move when the code does.
     */
    private String version(GatewayModelConfig config) {
        return config.updatedAt == null ? "family-default" : config.updatedAt.toString();
    }

    /**
     * @param version marker for render cache keys
     */
    public record RenderCaps(String model, MediaModelCapabilities capabilities, boolean nativeAudio,
                             Double maxOutputDurationSec, String version) {
        public boolean carriesImageReferences() {
            return capabilities.maxImages() == null || capabilities.maxImages() > 0;
        }
    }
}
