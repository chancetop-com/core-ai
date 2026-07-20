package ai.core.server.gateway;

import ai.core.media.MediaProvider;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.media.domain.VideoGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.domain.MediaJob;
import core.framework.web.exception.BadRequestException;

import java.util.concurrent.ConcurrentHashMap;

import static ai.core.server.gateway.GatewaySupport.hasText;
import java.util.concurrent.ConcurrentMap;

/**
 * @author stephen
 */
public class GatewayMediaProvider implements MediaProvider {
    private static final int MAX_CACHED_UPSTREAM_PROVIDERS = 32;

    private final GatewayRoutingEngine routingEngine;
    private final GatewaySecretProtector secretProtector;
    private final MediaProviderAdapterFactory adapterFactory;
    private final MediaJobService mediaJobService;
    private final ConcurrentMap<String, MediaProvider> upstreamProviders = new ConcurrentHashMap<>();

    public GatewayMediaProvider(GatewayRoutingEngine routingEngine, GatewaySecretProtector secretProtector, MediaJobService mediaJobService) {
        this(routingEngine, secretProtector, new MediaProviderAdapterFactory(), mediaJobService);
    }

    GatewayMediaProvider(GatewayRoutingEngine routingEngine, GatewaySecretProtector secretProtector,
                         MediaProviderAdapterFactory adapterFactory, MediaJobService mediaJobService) {
        this.routingEngine = routingEngine;
        this.secretProtector = secretProtector;
        this.adapterFactory = adapterFactory;
        this.mediaJobService = mediaJobService;
    }

    @Override
    public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
        var endpoint = request.inputImages() != null && !request.inputImages().isEmpty() || request.mask() != null
                ? GatewayEndpointType.IMAGE_EDIT : GatewayEndpointType.IMAGE_GENERATION;
        var resolved = route(request.model(), endpoint);
        var upstream = upstreamProvider(resolved.provider());
        var rewritten = rewriteModel(request, resolved.upstreamModel());
        return upstream.generateImage(rewritten);
    }

    @Override
    public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
        return generateVideo(request, MediaJobOwner.UNKNOWN);
    }

    VideoGenerationResponse generateVideo(VideoGenerationRequest request, MediaJobOwner owner) {
        var parentJob = previousVideoJob(request.previousInteractionId(), owner);
        var resolved = parentJob == null ? route(request.model(), GatewayEndpointType.VIDEO_GENERATION)
                : new GatewayRoute(routingEngine.jobProvider(parentJob.providerId), parentJob.resolvedModel);
        var upstream = upstreamProvider(resolved.provider());
        var previousInteractionId = parentJob == null ? null : parentJob.upstreamVideoId;
        var rewritten = rewriteModel(request, resolved.upstreamModel(), previousInteractionId);
        var response = upstream.generateVideo(rewritten);
        if (response == null || !hasText(response.id())) throw new IllegalStateException("upstream video response is missing id");
        var job = mediaJobService.createVideoJob(owner, resolved, request.model(), response.id(), parentJob == null ? null : parentJob.id);
        var videoId = GatewayVideoHandle.encode(job.id);
        return new VideoGenerationResponse(videoId, response.status(), response.createdAt(), response.usage());
    }

    @Override
    public VideoStatusResponse getVideoStatus(String videoId) {
        var job = mediaJobService.get(GatewayVideoHandle.decode(videoId));
        var status = upstreamProvider(routingEngine.jobProvider(job.providerId)).getVideoStatus(job.upstreamVideoId);
        mediaJobService.updateVideoStatus(job, status);
        return new VideoStatusResponse(videoId, status.status(), status.progress(), status.error(), status.completedAt());
    }

    @Override
    public byte[] downloadVideo(String videoId) {
        var job = mediaJobService.get(GatewayVideoHandle.decode(videoId));
        return upstreamProvider(routingEngine.jobProvider(job.providerId)).downloadVideo(job.upstreamVideoId);
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
                request.inputImages(), request.mask(), request.providerExtra(), request.previousInteractionId());
    }

    private VideoGenerationRequest rewriteModel(VideoGenerationRequest request, String upstreamModel, String previousInteractionId) {
        return new VideoGenerationRequest(
                upstreamModel, request.prompt(), request.seconds(), request.size(),
                request.inputReferences(), request.providerExtra(), previousInteractionId);
    }

    private MediaJob previousVideoJob(String videoId, MediaJobOwner owner) {
        if (!hasText(videoId)) return null;
        var job = mediaJobService.get(GatewayVideoHandle.decode(videoId));
        if (owner != null && hasText(owner.userId()) && !owner.userId().equals(job.userId)) {
            throw new BadRequestException("video task does not belong to current user");
        }
        return job;
    }

    private MediaProvider upstreamProvider(GatewayProviderConfig provider) {
        var key = provider.id + ":" + provider.updatedAt;
        var cached = upstreamProviders.get(key);
        if (cached != null) return cached;
        if (upstreamProviders.size() >= MAX_CACHED_UPSTREAM_PROVIDERS) upstreamProviders.clear();
        return upstreamProviders.computeIfAbsent(key, ignored -> createUpstreamProvider(provider));
    }

    private MediaProvider createUpstreamProvider(GatewayProviderConfig provider) {
        var apiKey = secretProtector.unprotect(provider.apiKeyEncrypted != null ? provider.apiKeyEncrypted : provider.apiKey);
        var googleCredentials = secretProtector.unprotect(provider.googleCredentialsEncrypted);
        return adapterFactory.create(provider, apiKey == null ? "" : apiKey, googleCredentials);
    }

}
