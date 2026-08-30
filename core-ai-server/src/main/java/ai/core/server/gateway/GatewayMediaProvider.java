package ai.core.server.gateway;

import ai.core.media.MediaProvider;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.MediaReference;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.media.domain.VideoGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;
import ai.core.media.reference.ManagedReferenceProvider;
import ai.core.media.reference.MediaModality;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.domain.MediaJob;
import core.framework.web.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static ai.core.server.gateway.GatewaySupport.hasText;

/**
 * @author stephen
 */
public class GatewayMediaProvider implements MediaProvider, ManagedReferenceProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayMediaProvider.class);
    private static final int MAX_CACHED_UPSTREAM_PROVIDERS = 32;
    private static final Duration VIDEO_STATUS_CACHE_TTL = Duration.ofSeconds(5);

    private final GatewayRoutingEngine routingEngine;
    private final GatewaySecretProtector secretProtector;
    private final MediaProviderAdapterFactory adapterFactory;
    private final MediaJobService mediaJobService;
    private final MediaCostSettler costSettler;
    private final MediaReferenceResolver referenceResolver;
    private final GatewayReferenceCompiler referenceCompiler = new GatewayReferenceCompiler();
    private final ConcurrentMap<String, MediaProvider> upstreamProviders = new ConcurrentHashMap<>();

    public GatewayMediaProvider(GatewayRoutingEngine routingEngine, GatewaySecretProtector secretProtector,
                                MediaJobService mediaJobService, MediaCostSettler costSettler) {
        this(routingEngine, secretProtector, new MediaProviderAdapterFactory(), mediaJobService, costSettler);
    }

    GatewayMediaProvider(GatewayRoutingEngine routingEngine, GatewaySecretProtector secretProtector,
                         MediaProviderAdapterFactory adapterFactory, MediaJobService mediaJobService, MediaCostSettler costSettler) {
        this.routingEngine = routingEngine;
        this.secretProtector = secretProtector;
        this.adapterFactory = adapterFactory;
        this.mediaJobService = mediaJobService;
        this.costSettler = costSettler;
        this.referenceResolver = new MediaReferenceResolver(mediaJobService);
    }

    @Override
    public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
        return generateImage(request, MediaJobOwner.UNKNOWN);
    }

    ImageGenerationResponse generateImage(ImageGenerationRequest request, MediaJobOwner owner) {
        var endpoint = request.inputImages() != null && !request.inputImages().isEmpty() || request.mask() != null
                ? GatewayEndpointType.IMAGE_EDIT : GatewayEndpointType.IMAGE_GENERATION;
        var resolved = route(request.model(), endpoint);

        // representation is chosen here, where the destination provider is finally known
        var references = referenceResolver.resolve(request.inputImages(), MediaModality.IMAGE, resolved, owner, this::videoBytes);
        var mask = mask(request, resolved, owner);
        var compiled = referenceCompiler.compile(request.prompt(), references.references(), MediaModality.IMAGE,
                resolved.upstreamModel(), resolved.model());
        var interactionId = hasText(request.previousInteractionId()) ? request.previousInteractionId() : references.interactionId();

        var upstream = upstreamProvider(resolved.provider());
        var response = upstream.generateImage(rewrite(request, resolved.upstreamModel(), compiled, mask, interactionId));
        var job = recordImageCost(request, resolved, response, owner);
        return response.with(job == null ? null : GatewayMediaHandle.encodeImage(job.id), compiled.notes());
    }

    private MediaReference mask(ImageGenerationRequest request, GatewayRoute route, MediaJobOwner owner) {
        if (request.mask() == null) return null;
        var resolved = referenceResolver.resolve(List.of(request.mask()), MediaModality.IMAGE, route, owner, this::videoBytes);
        return resolved.references().isEmpty() ? null : resolved.references().getFirst();
    }

    private MediaJob recordImageCost(ImageGenerationRequest request, GatewayRoute resolved, ImageGenerationResponse response, MediaJobOwner owner) {
        try {
            var price = costSettler.settleImage(request.model(), resolved.upstreamModel(), response.usage(),
                    response.data() == null ? 0 : response.data().size());
            return mediaJobService.createImageJob(owner, resolved, request.model(), price, response);
        } catch (RuntimeException e) {
            LOGGER.warn("image cost recording failed, model={}", request.model(), e);
            return null;
        }
    }

    @Override
    public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
        return generateVideo(request, MediaJobOwner.UNKNOWN);
    }

    VideoGenerationResponse generateVideo(VideoGenerationRequest request, MediaJobOwner owner) {
        var parentJob = previousVideoJob(request.previousInteractionId(), owner);
        var resolved = parentJob == null ? route(request.model(), GatewayEndpointType.VIDEO_GENERATION)
                : new GatewayRoute(routingEngine.jobProvider(parentJob.providerId), parentJob.resolvedModel,
                        routingEngine.modelConfig(request.model()));

        var references = referenceResolver.resolve(request.inputReferences(), MediaModality.IMAGE, resolved, owner, this::videoBytes);
        var compiled = referenceCompiler.compile(request.prompt(), references.references(), MediaModality.IMAGE,
                resolved.upstreamModel(), resolved.model());
        var previousInteractionId = parentJob != null ? parentJob.upstreamVideoId : references.interactionId();

        var upstream = upstreamProvider(resolved.provider());
        var response = upstream.generateVideo(rewrite(request, resolved.upstreamModel(), compiled, previousInteractionId));
        if (response == null || !hasText(response.id())) throw new IllegalStateException("upstream video response is missing id");
        var job = mediaJobService.createVideoJob(owner, resolved, request.model(), response.id(),
                parentJob == null ? null : parentJob.id, request.seconds());
        var videoId = GatewayMediaHandle.encodeVideo(job.id);
        return new VideoGenerationResponse(videoId, response.status(), response.createdAt(), response.usage(), compiled.notes());
    }

    @Override
    public VideoStatusResponse getVideoStatus(String videoId) {
        var job = mediaJobService.get(GatewayVideoHandle.decode(videoId));
        // serve the persisted status within the TTL to avoid hammering upstream providers with rate limits (e.g. KIE)
        if (job.updatedAt != null && job.updatedAt.isAfter(ZonedDateTime.now().minus(VIDEO_STATUS_CACHE_TTL))) {
            var completedAt = job.completedAt == null ? null : job.completedAt.toInstant().toEpochMilli();
            return new VideoStatusResponse(videoId, job.state, job.progress, job.error, completedAt);
        }
        var status = upstreamProvider(routingEngine.jobProvider(job.providerId)).getVideoStatus(job.upstreamVideoId);
        mediaJobService.updateVideoStatus(job, status);
        return new VideoStatusResponse(videoId, status.status(), status.progress(), status.error(), status.completedAt());
    }

    @Override
    public byte[] downloadVideo(String videoId) {
        var job = mediaJobService.get(GatewayVideoHandle.decode(videoId));
        return videoBytes(job);
    }

    private byte[] videoBytes(MediaJob job) {
        return upstreamProvider(routingEngine.jobProvider(job.providerId)).downloadVideo(job.upstreamVideoId);
    }

    private GatewayRoute route(String model, GatewayEndpointType endpoint) {
        if (!routingEngine.hasEnabledProviders())
            throw new BadRequestException("no enabled gateway providers configured for media generation");
        return routingEngine.route(model, endpoint);
    }

    private ImageGenerationRequest rewrite(ImageGenerationRequest request, String upstreamModel,
                                           GatewayReferenceCompiler.Compiled compiled, MediaReference mask, String interactionId) {
        return new ImageGenerationRequest(
                upstreamModel, compiled.prompt(), request.n(), request.size(), request.quality(),
                request.outputFormat(), request.outputCompression(), request.background(),
                compiled.references().isEmpty() ? null : compiled.references(), mask, request.providerExtra(), interactionId);
    }

    private VideoGenerationRequest rewrite(VideoGenerationRequest request, String upstreamModel,
                                           GatewayReferenceCompiler.Compiled compiled, String previousInteractionId) {
        return new VideoGenerationRequest(
                upstreamModel, compiled.prompt(), request.seconds(), request.size(),
                compiled.references().isEmpty() ? null : compiled.references(), request.providerExtra(), previousInteractionId);
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
