package ai.core.server.gateway;

import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ReasoningEffort;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.domain.ResponseFormat;
import ai.core.llm.domain.RoleType;
import ai.core.llm.providers.LiteLLMProvider;
import ai.core.llm.streaming.DefaultStreamingCallback;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.utils.JsonUtil;
import core.framework.web.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static ai.core.server.gateway.GatewaySupport.hasText;
import static ai.core.server.gateway.GatewaySupport.stripTrailingSlash;
import static ai.core.server.gateway.GatewaySupport.urlEncode;

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
    private static final List<String> REASONING_EFFORT_ORDER = List.of("minimal", "low", "medium", "high", "xhigh", "max");

    private static String resolveReasoningEffort(ReasoningEffort effort, List<String> supported) {
        if (effort == null || effort == ReasoningEffort.NONE) return null;
        if (supported == null || supported.isEmpty()) return null;
        var ranked = supported.stream().sorted(Comparator.comparingInt(GatewayLLMProvider::rank)).toList();
        if (effort == ReasoningEffort.MAX) return ranked.getLast();
        var target = rank(effort);
        return ranked.stream().filter(value -> rank(value) >= target).findFirst().orElse(ranked.getLast());
    }

    private static int rank(ReasoningEffort effort) {
        return switch (effort) {
            case NONE -> -1;
            case LOW -> REASONING_EFFORT_ORDER.indexOf("low");
            case HIGH -> REASONING_EFFORT_ORDER.indexOf("high");
            case MAX -> REASONING_EFFORT_ORDER.size();
        };
    }

    private static int rank(String value) {
        var index = REASONING_EFFORT_ORDER.indexOf(value);
        return index < 0 ? REASONING_EFFORT_ORDER.size() : index;
    }

    private final GatewayRoutingEngine routingEngine;
    private final GatewaySecretProtector secretProtector;
    private final LLMProvider fallback;
    private final Map<String, LiteLLMProvider> upstreamProviders = new ConcurrentHashMap<>();
    private final Map<String, ai.core.media.GoogleAccessTokenProvider> vertexTokenProviders = new ConcurrentHashMap<>();

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
        // FILE content (PDF attachments) is only expressible through the responses transport;
        // derive the endpoint when the caller did not specify one
        var endpoint = request.getEndpoint();
        if (endpoint == null && CompletionRequest.containsFileContent(request.messages)) {
            endpoint = GatewayModelService.ENDPOINT_RESPONSES;
        }
        var resolved = resolveRoute(request.model, endpoint);
        if (resolved == null) return fallbackCompletionStream(request, callback);
        var provider = resolved.route().provider();
        var upstreamModel = upstreamModel(resolved);
        var upstream = upstreamProvider(provider, upstreamModel, endpoint);
        var originalModel = request.model;
        if ("gemini".equals(provider.type) && GatewaySupport.isVertexGeminiBaseUrl(provider.baseUrl)) {
            // Vertex short-lived OAuth tokens expire within the hour; refresh before every call
            upstream.updateCredentials(GatewaySupport.geminiOpenAiCompatibleUrl(provider), vertexTokenProvider(provider).accessToken());
            // Vertex OpenAI-compatible endpoints address models as google/{model}
            request.model = "google/" + upstreamModel;
        } else {
            request.model = upstreamModel;
        }
        var modelConfig = routingEngine.modelConfig(originalModel);
        applyReasoningEffort(request, modelConfig);
        applyResponseFormat(request, modelConfig);
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

    // reasoning effort is model-specific: internal levels (none/low/high/max) are mapped to the
    // closest level the routed model declares in reasoningEfforts; an empty list means the model
    // does not support reasoning effort, so the field is omitted entirely
    private void applyReasoningEffort(CompletionRequest request, GatewayModelConfig modelConfig) {
        if (modelConfig == null || modelConfig.reasoningEfforts == null) return;
        var effort = request.reasoningEffort;
        request.reasoningEffort = null;
        request.setReasoningEffortValue(resolveReasoningEffort(effort, modelConfig.reasoningEfforts));
    }

    // JSON-mode models (e.g. DeepSeek) reject response_format type json_schema; downgrade to
    // json_object and constrain the output via the prompt instead of upstream schema validation
    private void applyResponseFormat(CompletionRequest request, GatewayModelConfig modelConfig) {
        if (modelConfig == null || !GatewayModelService.RESPONSE_FORMAT_JSON_OBJECT.equals(modelConfig.responseFormat)) return;
        if (request.responseFormat == null || !GatewayModelService.RESPONSE_FORMAT_JSON_SCHEMA.equals(request.responseFormat.type)) return;
        var schema = request.responseFormat.jsonSchema;
        var schemaJson = schema == null || schema.schema == null ? null : JsonUtil.toJson(schema.schema);
        request.responseFormat = ResponseFormat.jsonObject();
        if (schemaJson == null) return;
        appendSystemInstruction(request, "Return a JSON object that conforms to the following JSON schema:\n" + schemaJson);
    }

    private void appendSystemInstruction(CompletionRequest request, String instruction) {
        var systemMessage = request.messages.stream()
                .filter(message -> message.role == RoleType.SYSTEM)
                .findFirst();
        if (systemMessage.isPresent()) {
            var message = systemMessage.get();
            // message content is immutable (List.of), replace it with a mutable copy before appending
            message.content = new ArrayList<>(message.content == null ? List.of() : message.content);
            message.content.add(Content.of(instruction));
            return;
        }
        request.messages = new ArrayList<>(request.messages);
        request.messages.addFirst(Message.of(RoleType.SYSTEM, instruction));
    }

    private ResolvedRoute resolveRoute(String model, String endpoint) {
        // a registered-but-disabled model must stay blocked; without this check it could still be
        // served by legacy prefix routing or the static fallback, bypassing the admin's disable
        if (routingEngine.knowsModel(model) && !routingEngine.isRoutable(model)) {
            throw new BadRequestException("gateway model is disabled: " + model);
        }
        if (GatewayModelService.ENDPOINT_RESPONSES.equals(endpoint)) {
            var responsesRoute = responsesRoute(model);
            if (responsesRoute != null) return responsesRoute;
            if (routingEngine.knowsModel(model)) {
                throw new BadRequestException("gateway model does not support the responses endpoint, "
                        + "enable it on the model or use a responses-capable model: " + model);
            }
            // model unknown to the gateway — let the static provider decide, the request
            // endpoint drives its transport
            return null;
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
        // transport is driven by the request endpoint, so the upstream model name is always the
        // real model/deployment name; the responses/ routing-convention prefix is only a static-provider fallback
        return resolved.route().upstreamModel();
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

    private LiteLLMProvider upstreamProvider(GatewayProviderConfig provider, String upstreamModel, String endpoint) {
        // updatedAt is part of the key, so config changes naturally invalidate cached upstream clients;
        // for azure, the upstreamModel and endpoint must be in the cache key because the URL is model/endpoint-specific
        var key = "azure".equals(provider.type)
                ? provider.id + ":" + upstreamModel + ":" + endpoint + ":" + provider.updatedAt
                : provider.id + ":" + endpoint + ":" + provider.updatedAt;
        var cached = upstreamProviders.get(key);
        if (cached != null) return cached;
        if (upstreamProviders.size() >= MAX_CACHED_UPSTREAM_PROVIDERS) upstreamProviders.clear();
        return upstreamProviders.computeIfAbsent(key, ignored -> createUpstreamProvider(provider, upstreamModel, endpoint));
    }

    LiteLLMProvider createUpstreamProvider(GatewayProviderConfig provider, String upstreamModel, String endpoint) {
        // fresh config: the static provider's extra-body/model settings must not leak to gateway upstreams
        var upstreamConfig = new LLMProviderConfig(null, config.getTemperature(), null);
        // reasoning models stay silent for minutes while thinking: enforce a floor on the upstream
        // timeout — a small value (e.g. legacy 30s) makes okhttp's callTimeout (connect+timeout+2s)
        // cut the SSE stream after ~42s, which survives retries into a "3 minute failure"
        var timeout = provider.timeoutSeconds != null ? Math.max(provider.timeoutSeconds, 600L) : 900L;
        upstreamConfig.setTimeout(timeout);
        if (provider.connectTimeoutSeconds != null) upstreamConfig.setConnectTimeout(provider.connectTimeoutSeconds);
        if (hasText(provider.requestExtraBody)) upstreamConfig.setRequestExtraBody(provider.requestExtraBody);
        var apiKey = secretProtector.unprotect(provider.apiKeyEncrypted != null ? provider.apiKeyEncrypted : provider.apiKey);
        var key = apiKey == null ? "" : apiKey;
        if ("azure".equals(provider.type)) {
            var resourceBase = stripTrailingSlash(provider.baseUrl);
            // azure provider baseUrl typically ends with /openai/v1, strip /v1 to get resource root
            if (resourceBase.endsWith("/v1")) resourceBase = resourceBase.substring(0, resourceBase.length() - 3);
            var version = hasText(provider.apiVersion) ? provider.apiVersion : "2024-10-21";
            if (GatewayModelService.ENDPOINT_RESPONSES.equals(endpoint)) {
                // the responses endpoint is resource-level on azure: /openai/responses, model in the body
                var responsesUrl = resourceBase + "/responses?api-version=" + urlEncode(version);
                return new LiteLLMProvider(upstreamConfig, responsesUrl, key, "api-key", "");
            }
            var azureUrl = resourceBase + "/deployments/" + urlEncode(upstreamModel) + "/chat/completions?api-version=" + urlEncode(version);
            return new LiteLLMProvider(upstreamConfig, azureUrl, key, "api-key", "");
        }
        if ("gemini".equals(provider.type)) {
            return createGeminiProvider(provider, endpoint, upstreamConfig);
        }
        return new LiteLLMProvider(upstreamConfig, provider.baseUrl, key);
    }

    // gemini providers point at Google's native REST root (Vertex or Developer API); both serve
    // chat completions through their OpenAI-compatible endpoints, never at {baseUrl}/chat/completions
    private LiteLLMProvider createGeminiProvider(GatewayProviderConfig provider, String endpoint, LLMProviderConfig upstreamConfig) {
        if (GatewayModelService.ENDPOINT_RESPONSES.equals(endpoint)) {
            throw new BadRequestException("gemini provider does not support the responses endpoint: " + provider.name);
        }
        var url = GatewaySupport.geminiOpenAiCompatibleUrl(provider);
        if (GatewaySupport.isVertexGeminiBaseUrl(provider.baseUrl)) {
            return new LiteLLMProvider(upstreamConfig, url, vertexTokenProvider(provider).accessToken());
        }
        var apiKey = secretProtector.unprotect(provider.apiKeyEncrypted != null ? provider.apiKeyEncrypted : provider.apiKey);
        return new LiteLLMProvider(upstreamConfig, url, apiKey == null ? "" : apiKey);
    }

    ai.core.media.GoogleAccessTokenProvider vertexTokenProvider(GatewayProviderConfig provider) {
        return vertexTokenProviders.computeIfAbsent(provider.id, ignored -> {
            var credentials = provider.googleCredentialsEncrypted == null ? null : secretProtector.unprotect(provider.googleCredentialsEncrypted);
            return new ai.core.media.GoogleAccessTokenProvider("GOOGLE_SERVICE_ACCOUNT_JSON".equals(provider.mediaAuthType) ? credentials : null);
        });
    }

    private record ResolvedRoute(GatewayRoute route, boolean responses) {
    }
}
