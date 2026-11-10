package ai.core.server.gateway;

import ai.core.decision.DecisionProvider;
import ai.core.decision.SystemOneDecisionProvider;
import ai.core.server.domain.GatewayProviderConfig;
import core.framework.web.exception.BadRequestException;

import java.util.Locale;

import static ai.core.server.gateway.GatewaySupport.hasText;

/**
 * Maps a provider's decision profile to the full request path and credential header. The response shape
 * is identical across System One vendors, so every profile resolves to the same adapter.
 *
 * @author stephen
 */
public class DecisionProviderAdapterFactory {
    static final String AZURE_FOUNDRY_DECISION = "AZURE_FOUNDRY_DECISION";
    static final String SYSTEMONE = "SYSTEMONE";
    static final String OPENROUTER_DECISIONS = "OPENROUTER_DECISIONS";

    static String protocol(GatewayProviderConfig provider) {
        if (hasText(provider.decisionProtocol)) return provider.decisionProtocol.trim().toUpperCase(Locale.ROOT);
        var type = provider.type == null ? "" : provider.type;
        return switch (type) {
            case "azure" -> AZURE_FOUNDRY_DECISION;
            case "openai", "openai-compatible", "litellm", "deepseek", "qwen" -> SYSTEMONE;
            case "openrouter" -> OPENROUTER_DECISIONS;
            default -> throw new BadRequestException("decisionProtocol is required for provider type: " + provider.type);
        };
    }

    static String path(String protocol) {
        return switch (protocol) {
            case AZURE_FOUNDRY_DECISION -> "/providers/microsoft/v1/systemone";
            case SYSTEMONE -> "/v1/systemone";
            case OPENROUTER_DECISIONS -> "/api/alpha/decisions";
            default -> throw new BadRequestException("unsupported decision protocol: " + protocol);
        };
    }

    static String credentialHeader(String protocol) {
        return AZURE_FOUNDRY_DECISION.equals(protocol) ? "api-key" : "Authorization";
    }

    /**
     * Azure chat providers point at {@code .../openai/v1}; the System One endpoint is resource-level, so
     * reuse of an existing Azure provider requires stripping the chat API suffix.
     */
    static String baseUrl(GatewayProviderConfig provider, String protocol) {
        var baseUrl = GatewaySupport.stripTrailingSlash(provider.baseUrl);
        if (!AZURE_FOUNDRY_DECISION.equals(protocol)) return baseUrl;
        if (baseUrl.endsWith("/openai/v1")) return baseUrl.substring(0, baseUrl.length() - "/openai/v1".length());
        if (baseUrl.endsWith("/openai")) return baseUrl.substring(0, baseUrl.length() - "/openai".length());
        return baseUrl;
    }

    public DecisionProvider create(GatewayProviderConfig provider, String apiKey) {
        var protocol = protocol(provider);
        GatewayNetworkGuard.validateOutboundUrl(provider.baseUrl, Boolean.TRUE.equals(provider.allowPrivateNetwork));
        return new SystemOneDecisionProvider(baseUrl(provider, protocol), apiKey, credentialHeader(protocol), path(protocol));
    }
}
