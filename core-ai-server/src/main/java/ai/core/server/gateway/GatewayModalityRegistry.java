package ai.core.server.gateway;

import ai.core.llm.InputModality;
import ai.core.llm.ModalitySupport;
import ai.core.llm.ModelModalityRegistry;
import ai.core.llm.SeedModelModalityRegistry;

/**
 * Gateway-backed modality registry: an admin declaration on the gateway model wins,
 * otherwise falls back to the litellm seed keyed by the upstream model name.
 *
 * @author Xander
 */
public class GatewayModalityRegistry implements ModelModalityRegistry {
    private final GatewayRoutingEngine routingEngine;

    public GatewayModalityRegistry(GatewayRoutingEngine routingEngine) {
        this.routingEngine = routingEngine;
    }

    @Override
    public ModalitySupport supports(String model, InputModality modality) {
        if (modality == InputModality.TEXT) return ModalitySupport.SUPPORTED;
        if (model == null || model.isBlank()) return ModalitySupport.UNKNOWN;
        var config = routingEngine.modelConfig(model);
        if (config == null) return SeedModelModalityRegistry.INSTANCE.supports(model, modality);
        var declared = switch (modality) {
            case IMAGE -> config.supportsVision;
            case FILE -> config.supportsFile;
            case VIDEO -> config.supportsVideo;
            default -> null;
        };
        if (declared != null) return declared ? ModalitySupport.SUPPORTED : ModalitySupport.UNSUPPORTED;
        var upstream = config.upstreamModel != null ? config.upstreamModel : model;
        return SeedModelModalityRegistry.INSTANCE.supports(upstream, modality);
    }
}
