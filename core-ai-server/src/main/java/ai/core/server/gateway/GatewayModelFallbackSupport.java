package ai.core.server.gateway;

import ai.core.api.server.gateway.GatewayModelRequest;
import ai.core.server.domain.GatewayModelConfig;
import core.framework.web.exception.BadRequestException;

import java.util.List;

/**
 * Normalizes and validates the ordered fallback chain configured on a gateway model.
 */
final class GatewayModelFallbackSupport {
    static void apply(GatewayModelConfig entity, GatewayModelRequest request, boolean create, GatewayModelStore models) {
        if (!create && !request.hasField("fallbackModelIds")) return;
        entity.fallbackModelIds = normalize(request.fallbackModelIds);
        validate(entity, models);
    }

    static List<String> normalize(List<String> fallbackModelIds) {
        if (fallbackModelIds == null || fallbackModelIds.isEmpty()) return null;
        var values = fallbackModelIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        return values.isEmpty() ? null : values;
    }

    // a typo in the chain would silently disable failover, so every fallback must be an existing model
    static void validate(GatewayModelConfig entity, GatewayModelStore models) {
        if (entity.fallbackModelIds == null) return;
        for (var fallbackModelId : entity.fallbackModelIds) {
            if (fallbackModelId.equals(entity.modelId)) {
                throw new BadRequestException("gateway model must not fall back to itself: " + fallbackModelId);
            }
            if (models.listByModelId(fallbackModelId).isEmpty()) {
                throw new BadRequestException("fallback gateway model does not exist: " + fallbackModelId);
            }
        }
    }

    private GatewayModelFallbackSupport() {
    }
}
