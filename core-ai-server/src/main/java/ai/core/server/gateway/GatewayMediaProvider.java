package ai.core.server.gateway;

import ai.core.llm.providers.LiteLLMMediaProvider;
import ai.core.media.MediaProvider;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.media.domain.VideoGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;
import ai.core.server.domain.GatewayProviderConfig;
import core.framework.web.exception.BadRequestException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author stephen
 */
public class GatewayMediaProvider implements MediaProvider {
    private static final int MAX_CACHED_UPSTREAM_PROVIDERS = 32;

    private final GatewayRoutingEngine routingEngine;
    private final GatewaySecretProtector secretProtector;
    private final ConcurrentMap<String, LiteLLMMediaProvider> upstreamProviders = new ConcurrentHashMap<>();

    public GatewayMediaProvider(GatewayRoutingEngine routingEngine, GatewaySecretProtector secretProtector) {
        this.routingEngine = routingEngine;
        this.secretProtector = secretProtector;
    }

    @Override
    public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
        var resolved = route(request.model(), GatewayEndpointType.IMAGE_GENERATION);
        var upstream = upstreamProvider(resolved.provider());
        var rewritten = rewriteModel(request, resolved.upstreamModel());
        return upstream.generateImage(rewritten);
    }

    @Override
    public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
        var resolved = route(request.model(), GatewayEndpointType.VIDEO_GENERATION);
        var upstream = upstreamProvider(resolved.provider());
        var rewritten = rewriteModel(request, resolved.upstreamModel());
        return upstream.generateVideo(rewritten);
    }

    @Override
    public VideoStatusResponse getVideoStatus(String videoId) {
        // delegate to first cached upstream — video IDs are provider-scoped
        var upstream = firstUpstream();
        return upstream.getVideoStatus(videoId);
    }

    @Override
    public byte[] downloadVideo(String videoId) {
        var upstream = firstUpstream();
        return upstream.downloadVideo(videoId);
    }

    private GatewayRoute route(String model, GatewayEndpointType endpoint) {
        if (!routingEngine.hasEnabledProviders())
            throw new BadRequestException("no enabled gateway providers configured for media generation");
        return routingEngine.route(model, endpoint);
    }

    private ImageGenerationRequest rewriteModel(ImageGenerationRequest request, String upstreamModel) {
        return new ImageGenerationRequest(
                upstreamModel, request.prompt(), request.n(), request.size(), request.quality(),
                request.outputFormat(), request.outputCompression(), request.background(),
                request.inputImages(), request.mask(), request.providerExtra());
    }

    private VideoGenerationRequest rewriteModel(VideoGenerationRequest request, String upstreamModel) {
        return new VideoGenerationRequest(
                upstreamModel, request.prompt(), request.seconds(), request.size(),
                request.inputReferences(), request.providerExtra());
    }

    private LiteLLMMediaProvider upstreamProvider(GatewayProviderConfig provider) {
        var key = provider.id + ":" + provider.updatedAt;
        var cached = upstreamProviders.get(key);
        if (cached != null) return cached;
        if (upstreamProviders.size() >= MAX_CACHED_UPSTREAM_PROVIDERS) upstreamProviders.clear();
        return upstreamProviders.computeIfAbsent(key, ignored -> createUpstreamProvider(provider));
    }

    private LiteLLMMediaProvider createUpstreamProvider(GatewayProviderConfig provider) {
        var apiKey = secretProtector.unprotect(provider.apiKeyEncrypted != null ? provider.apiKeyEncrypted : provider.apiKey);
        return new LiteLLMMediaProvider(provider.baseUrl, apiKey == null ? "" : apiKey);
    }

    private LiteLLMMediaProvider firstUpstream() {
        if (upstreamProviders.isEmpty())
            throw new BadRequestException("no upstream media providers available — generate video first");
        return upstreamProviders.values().iterator().next();
    }
}
