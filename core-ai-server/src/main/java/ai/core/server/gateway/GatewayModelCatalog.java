package ai.core.server.gateway;

import ai.core.llm.LLMModelContextRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class GatewayModelCatalog {
    static GatewayModelMetadata enrich(GatewayModelMetadata source) {
        var endpoints = source.endpointTypes();
        if (endpoints == null || endpoints.isEmpty()) endpoints = inferEndpoints(source.id());
        var stream = source.supportsStream() != null ? source.supportsStream() : Boolean.TRUE;
        // provider-declared capabilities win; the bundled litellm seed only fills the gaps
        var seed = LLMModelContextRegistry.getInstance().getModelInfo(source.id());
        var supportsVision = source.supportsVision() != null ? source.supportsVision() : seed == null ? null : seed.supportsVision();
        var supportsFile = source.supportsFile() != null ? source.supportsFile() : seed == null ? null : seed.supportsPdfInput();
        return new GatewayModelMetadata(
                source.id(),
                source.displayName(),
                endpoints,
                source.contextWindow(),
                stream,
                source.supportsTools(),
                supportsVision,
                supportsFile,
                source.inputPricePer1MTokens(),
                source.outputPricePer1MTokens()
        );
    }

    private static List<String> inferEndpoints(String modelId) {
        var value = modelId == null ? "" : modelId.toLowerCase(Locale.ROOT);
        if (containsAny(value, "embedding", "embed", "whisper", "tts", "image", "moderation")) return List.of();
        var endpoints = new ArrayList<String>();
        endpoints.add(GatewayModelService.ENDPOINT_CHAT_COMPLETIONS);
        if (value.matches("o\\d.*") || value.startsWith("gpt-4.1") || value.startsWith("gpt-5")) {
            endpoints.add(GatewayModelService.ENDPOINT_RESPONSES);
        }
        return endpoints;
    }

    private static boolean containsAny(String value, String... tokens) {
        for (var token : tokens) {
            if (value.contains(token)) return true;
        }
        return false;
    }

    private GatewayModelCatalog() {
    }
}
