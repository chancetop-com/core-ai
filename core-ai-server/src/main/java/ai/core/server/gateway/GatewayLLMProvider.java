package ai.core.server.gateway;

import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.providers.LiteLLMProvider;
import ai.core.llm.streaming.DefaultStreamingCallback;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.server.domain.GatewayProviderConfig;
import core.framework.web.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static ai.core.server.gateway.GatewaySupport.hasText;

/**
 * Bridges the agent runtime {@link LLMProvider} interface onto gateway-managed providers:
 * requests are routed by gateway modelId to the configured upstream, so agents can run
 * any model registered in the gateway. Falls back to the statically configured provider
 * when the gateway has no matching route.
 */
public class GatewayLLMProvider extends LLMProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayLLMProvider.class);
    private static final int MAX_CACHED_UPSTREAM_PROVIDERS = 32;

    private final GatewayRoutingEngine routingEngine;
    private final GatewaySecretProtector secretProtector;
    private final LLMProvider fallback;
    private final Map<String, LiteLLMProvider> upstreamProviders = new ConcurrentHashMap<>();

    public GatewayLLMProvider(LLMProviderConfig config, GatewayRoutingEngine routingEngine, GatewaySecretProtector secretProtector, LLMProvider fallback) {
        super(config);
        this.routingEngine = routingEngine;
        this.secretProtector = secretProtector;
        this.fallback = fallback;
    }

    @Override
    protected CompletionResponse doCompletion(CompletionRequest request) {
        return doCompletionStream(request, new DefaultStreamingCallback());
    }

    @Override
    protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
        var route = resolveRoute(request.model);
        if (route == null) return fallbackCompletionStream(request, callback);
        if ("azure".equals(route.provider().type)) {
            throw new BadRequestException("azure gateway provider is not supported for agent runtime yet: " + route.provider().name);
        }
        var upstream = upstreamProvider(route.provider());
        request.model = route.upstreamModel();
        // re-apply model-specific request adjustments now that the upstream model name is known
        preprocess(request);
        return upstream.delegateCompletionStream(request, callback);
    }

    @Override
    public EmbeddingResponse embeddings(EmbeddingRequest request) {
        if (fallback == null) throw new IllegalStateException("gateway does not support embeddings yet and no fallback LLM provider is configured");
        return fallback.embeddings(request);
    }

    @Override
    public RerankingResponse rerankings(RerankingRequest request) {
        if (fallback == null) throw new IllegalStateException("gateway does not support rerankings yet and no fallback LLM provider is configured");
        return fallback.rerankings(request);
    }

    @Override
    public CaptionImageResponse captionImage(CaptionImageRequest request) {
        if (fallback == null) throw new IllegalStateException("gateway does not support image caption yet and no fallback LLM provider is configured");
        return fallback.captionImage(request);
    }

    @Override
    public String name() {
        return "gateway";
    }

    private GatewayRoute resolveRoute(String model) {
        try {
            return routingEngine.route(model, GatewayEndpointType.CHAT_COMPLETIONS);
        } catch (BadRequestException e) {
            var responsesRoute = responsesRoute(model);
            if (responsesRoute != null) return responsesRoute;
            if (fallback == null) throw e;
            LOGGER.warn("no gateway route for model, falling back to static LLM provider, model={}, error={}", model, e.getMessage());
            return null;
        }
    }

    // responses-only models still go through LiteLLMProvider, whose responses bridge picks the transport by model name
    private GatewayRoute responsesRoute(String model) {
        if (!hasText(model)) return null;
        try {
            return routingEngine.route(model, GatewayEndpointType.RESPONSES);
        } catch (BadRequestException e) {
            return null;
        }
    }

    private CompletionResponse fallbackCompletionStream(CompletionRequest request, StreamingCallback callback) {
        if (fallback instanceof LiteLLMProvider liteLLMProvider) {
            return liteLLMProvider.delegateCompletionStream(request, callback);
        }
        throw new IllegalStateException("unsupported fallback LLM provider: " + (fallback == null ? "none" : fallback.name()));
    }

    private LiteLLMProvider upstreamProvider(GatewayProviderConfig provider) {
        // updatedAt is part of the key, so config changes naturally invalidate cached upstream clients
        var key = provider.id + ":" + provider.updatedAt;
        var cached = upstreamProviders.get(key);
        if (cached != null) return cached;
        if (upstreamProviders.size() >= MAX_CACHED_UPSTREAM_PROVIDERS) upstreamProviders.clear();
        return upstreamProviders.computeIfAbsent(key, ignored -> createUpstreamProvider(provider));
    }

    LiteLLMProvider createUpstreamProvider(GatewayProviderConfig provider) {
        var upstreamConfig = new LLMProviderConfig(config);
        if (provider.timeoutSeconds != null) upstreamConfig.setTimeout(provider.timeoutSeconds);
        if (provider.connectTimeoutSeconds != null) upstreamConfig.setConnectTimeout(provider.connectTimeoutSeconds);
        if (hasText(provider.requestExtraBody)) upstreamConfig.setRequestExtraBody(provider.requestExtraBody);
        var apiKey = secretProtector.unprotect(provider.apiKeyEncrypted != null ? provider.apiKeyEncrypted : provider.apiKey);
        return new LiteLLMProvider(upstreamConfig, provider.baseUrl, apiKey == null ? "" : apiKey);
    }
}
