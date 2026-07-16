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
 * only when the gateway does not know the model at all — a registered-but-disabled model
 * stays blocked so admin enable/disable is enforced on the agent path too.
 */
public class GatewayLLMProvider extends LLMProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayLLMProvider.class);
    private static final int MAX_CACHED_UPSTREAM_PROVIDERS = 32;
    // model prefix that makes LiteLLMProvider pick the /responses transport; stripped before sending upstream
    private static final String RESPONSES_MODEL_PREFIX = "responses/";

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

    /**
     * Deferred on purpose: the base template calls preprocess before doCompletionStream,
     * when request.model is still the gateway alias. Model-specific request rewrites
     * (o1 system-role conversion, gpt-5 temperature) must run against the real upstream
     * model name, so doCompletionStream applies {@link #applyPreprocess} after routing.
     */
    @Override
    public void preprocess(CompletionRequest request) {
    }

    @Override
    protected CompletionResponse doCompletion(CompletionRequest request) {
        return doCompletionStream(request, new DefaultStreamingCallback());
    }

    @Override
    protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
        // fast path: gateway unconfigured deployments go straight to the static provider,
        // without exception-driven routing attempts or log noise on every call
        if (!routingEngine.hasEnabledProviders()) return fallbackCompletionStream(request, callback);
        var resolved = resolveRoute(request.model);
        if (resolved == null) return fallbackCompletionStream(request, callback);
        var provider = resolved.route().provider();
        if ("azure".equals(provider.type)) {
            if (fallback != null) {
                LOGGER.warn("azure gateway provider is not supported for agent runtime yet, falling back to static LLM provider, model={}, provider={}", request.model, provider.name);
                return fallbackCompletionStream(request, callback);
            }
            throw new BadRequestException("azure gateway provider is not supported for agent runtime yet: " + provider.name);
        }
        var upstream = upstreamProvider(provider);
        var originalModel = request.model;
        request.model = upstreamModel(resolved);
        applyPreprocess(request);
        try {
            return upstream.delegateCompletionStream(request, callback);
        } finally {
            request.model = originalModel;
        }
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

    private void applyPreprocess(CompletionRequest request) {
        super.preprocess(request);
    }

    private ResolvedRoute resolveRoute(String model) {
        // a registered-but-disabled model must stay blocked; without this check it could still be
        // served by legacy prefix routing or the static fallback, bypassing the admin's disable
        if (routingEngine.knowsModel(model) && !routingEngine.isRoutable(model)) {
            throw new BadRequestException("gateway model is disabled: " + model);
        }
        try {
            return new ResolvedRoute(routingEngine.route(model, GatewayEndpointType.CHAT_COMPLETIONS), false);
        } catch (BadRequestException e) {
            var responsesRoute = responsesRoute(model);
            if (responsesRoute != null) return responsesRoute;
            if (fallback == null) throw e;
            LOGGER.warn("no gateway route for model, falling back to static LLM provider, model={}, error={}", model, e.getMessage());
            return null;
        }
    }

    private ResolvedRoute responsesRoute(String model) {
        try {
            return new ResolvedRoute(routingEngine.route(model, GatewayEndpointType.RESPONSES), true);
        } catch (BadRequestException e) {
            return null;
        }
    }

    private String upstreamModel(ResolvedRoute resolved) {
        var upstreamModel = resolved.route().upstreamModel();
        if (!resolved.responses() || LiteLLMProvider.isResponsesModel(upstreamModel)) return upstreamModel;
        return RESPONSES_MODEL_PREFIX + upstreamModel;
    }

    private CompletionResponse fallbackCompletionStream(CompletionRequest request, StreamingCallback callback) {
        if (fallback == null) {
            throw new BadRequestException("no LLM provider available for model, gateway has no enabled providers and no static provider is configured: " + request.model);
        }
        if (fallback instanceof LiteLLMProvider liteLLMProvider) {
            applyPreprocess(request);
            return liteLLMProvider.delegateCompletionStream(request, callback);
        }
        throw new IllegalStateException("unsupported fallback LLM provider: " + fallback.name());
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
        // fresh config: the static provider's extra-body/model settings must not leak to gateway upstreams
        var upstreamConfig = new LLMProviderConfig(null, config.getTemperature(), null);
        if (provider.timeoutSeconds != null) upstreamConfig.setTimeout(provider.timeoutSeconds);
        if (provider.connectTimeoutSeconds != null) upstreamConfig.setConnectTimeout(provider.connectTimeoutSeconds);
        if (hasText(provider.requestExtraBody)) upstreamConfig.setRequestExtraBody(provider.requestExtraBody);
        var apiKey = secretProtector.unprotect(provider.apiKeyEncrypted != null ? provider.apiKeyEncrypted : provider.apiKey);
        return new LiteLLMProvider(upstreamConfig, provider.baseUrl, apiKey == null ? "" : apiKey);
    }

    private record ResolvedRoute(GatewayRoute route, boolean responses) {
    }
}
