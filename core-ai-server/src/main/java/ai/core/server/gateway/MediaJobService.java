package ai.core.server.gateway;

import ai.core.media.domain.ImageData;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;
import ai.core.media.reference.HttpRemoteMediaLoader;
import ai.core.media.reference.MediaModality;
import ai.core.media.reference.RemoteMediaLoader;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.MediaJob;
import ai.core.server.domain.MediaJobInput;
import ai.core.server.file.FileService;
import ai.core.server.trace.service.MediaPricingService;
import ai.core.telemetry.TelemetryConfig;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static ai.core.server.gateway.GatewaySupport.hasText;

/**
 * @author Stephen
 */
public class MediaJobService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaJobService.class);
    static final Set<String> COST_SOURCES = Set.of("gateway_model", "upstream", "model_catalog", "unavailable");

    @Inject
    MediaJobStore mediaJobStore;
    @Inject
    MediaCostSettler costSettler;
    @Inject
    FileService fileService;
    @Inject
    MediaJobArtifactStore artifactStore;
    @Inject
    TelemetryConfig telemetryConfig;

    // only external/upstream result URLs go through this; platform-owned bytes come from FileService
    RemoteMediaLoader remoteMediaLoader = new HttpRemoteMediaLoader();

    public MediaJob createVideoJob(MediaJobOwner owner, GatewayRoute route, String requestedModel, VideoJobSubmission submission) {
        var jobOwner = owner == null ? MediaJobOwner.UNKNOWN : owner;
        var now = ZonedDateTime.now();
        var job = new MediaJob();
        job.id = UUID.randomUUID().toString();
        job.userId = jobOwner.userId();
        job.sessionId = jobOwner.sessionId();
        job.agentRunId = jobOwner.agentRunId();
        job.providerId = route.provider().id;
        job.upstreamVideoId = submission.upstreamVideoId();
        job.parentJobId = submission.parentJobId();
        job.requestedModel = requestedModel;
        job.resolvedModel = route.upstreamModel();
        job.prompt = submission.prompt();
        job.inputs = inputs(submission.inputs());
        job.requestedSize = submission.requestedSize();
        job.state = "submitted";
        job.mediaType = "video";
        job.requestedSeconds = submission.requestedSeconds();
        job.createdAt = now;
        job.updatedAt = now;
        mediaJobStore.insert(job);
        return job;
    }

    public MediaJob createImageJob(MediaJobOwner owner, GatewayRoute route, ImageJobSubmission submission) {
        var request = submission.request();
        var jobOwner = owner == null ? MediaJobOwner.UNKNOWN : owner;
        var now = ZonedDateTime.now();
        var job = new MediaJob();
        job.id = UUID.randomUUID().toString();
        job.userId = jobOwner.userId();
        job.sessionId = jobOwner.sessionId();
        job.agentRunId = jobOwner.agentRunId();
        job.providerId = route.provider().id;
        job.requestedModel = request.model();
        job.resolvedModel = route.upstreamModel();
        job.prompt = request.prompt();
        job.inputs = inputs(submission.inputs());
        job.requestedSize = request.size();
        job.requestedQuality = request.quality();
        job.requestedCount = request.n();
        job.outputFormat = request.outputFormat();
        job.outputCompression = request.outputCompression();
        job.background = request.background();
        job.state = "completed";
        job.mediaType = "image";
        job.createdAt = now;
        job.updatedAt = now;
        job.completedAt = now;
        var price = submission.price();
        job.mediaUnits = price.units();
        job.mediaUnitType = price.unitType();
        job.costUsd = price.costUsd();
        job.costSource = price.source();
        job.pricingModelId = price.pricingModelId();
        var response = submission.response();
        if (response != null) {
            job.upstreamInteractionId = response.interactionId();
            var image = response.data() == null || response.data().isEmpty() ? null : response.data().getFirst();
            if (image != null && hasText(image.url())) job.upstreamAssetUrl = image.url();
            storeImage(job, image);
        }
        mediaJobStore.insert(job);
        MediaGenerationTrace.record(telemetryConfig, job);
        return job;
    }

    private List<MediaJobInput> inputs(List<MediaJobInput> inputs) {
        return inputs == null || inputs.isEmpty() ? null : List.copyOf(inputs);
    }

    /**
     * Persists the generated image into object storage so it can be referenced later. Tier 3 hands the
     * provider a pre-signed URL of exactly this record, so a provider that returns only a result URL
     * must still be stored — otherwise images would stop being stored precisely when references start
     * depending on them.
     */
    private void storeImage(MediaJob job, ImageData image) {
        if (image == null) return;
        try {
            var bytes = imageBytes(image);
            if (bytes == null) return;
            var tempFile = Files.createTempFile("media-image-", ".bin");
            Files.write(tempFile, bytes);
            var contentType = imageContentType(image);
            var record = fileService.uploadIfAbsent(job.userId, "generated-image" + FileService.extension(contentType), contentType, tempFile);
            job.fileId = record.id;
            job.fileName = record.fileName;
            job.contentType = record.contentType;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("image artifact storage failed, model={}", job.requestedModel, e);
        }
    }

    private byte[] imageBytes(ImageData image) {
        if (hasText(image.b64Json())) return Base64.getDecoder().decode(dataPart(image.b64Json()));
        if (hasText(image.url())) return remoteMediaLoader.load(image.url()).data();
        return null;
    }

    private String imageContentType(ImageData image) {
        var value = image.b64Json();
        if (value == null || !value.startsWith("data:")) return "image/png";
        var separator = value.indexOf(',');
        if (separator <= 5) return "image/png";
        var metadata = value.substring(5, separator);
        var semicolon = metadata.indexOf(';');
        var mimeType = semicolon < 0 ? metadata : metadata.substring(0, semicolon);
        return mimeType.isBlank() ? "image/png" : mimeType;
    }

    private String dataPart(String value) {
        var comma = value.indexOf(',');
        return comma >= 0 && value.startsWith("data:") ? value.substring(comma + 1) : value;
    }

    public MediaJob get(String jobId) {
        return mediaJobStore.get(jobId)
                .orElseThrow(() -> new NotFoundException("media job not found, id=" + jobId));
    }

    public MediaJob getOwned(String jobId, String userId) {
        var job = get(jobId);
        if (userId == null || !userId.equals(job.userId)) {
            throw new ForbiddenException("media job does not belong to current user");
        }
        return job;
    }

    /**
     * Resolves {@code media_id="last"}: the newest completed media of this modality the caller produced
     * in this session. Falls back to the caller's own jobs when no session is in scope (CLI runs), and
     * never crosses users — a handle is an authorization check, unlike an arbitrary URL.
     */
    public Optional<MediaJob> findLatestCompleted(MediaJobOwner owner, MediaModality modality) {
        var jobOwner = owner == null ? MediaJobOwner.UNKNOWN : owner;
        String sessionId = hasText(jobOwner.sessionId()) ? jobOwner.sessionId() : null;
        String userId = hasText(jobOwner.userId()) ? jobOwner.userId() : null;
        return mediaJobStore.findLatestCompleted(sessionId, userId, modality == MediaModality.VIDEO ? "video" : "image");
    }

    /** Resolves a handle to a job the caller owns, with the failure the agent can act on. */
    public MediaJob resolveReference(String mediaId, MediaJobOwner owner) {
        var handle = GatewayMediaHandle.decode(mediaId);
        var job = mediaJobStore.get(handle.jobId())
                .orElseThrow(() -> new BadRequestException("unknown media reference: " + mediaId));
        var userId = owner == null ? null : owner.userId();
        if (hasText(job.userId) && !job.userId.equals(userId)) {
            throw new ForbiddenException("media reference does not belong to current user: " + mediaId);
        }
        if (!"completed".equals(job.state)) {
            throw new BadRequestException("media reference is not ready, state=" + job.state + ", media_id=" + mediaId);
        }
        return job;
    }

    /**
     * Lenient, ownerless handle resolution for server-stored references (drama identity stills,
     * render inputs): the tool output tells the agent to reuse the media_id, so stores that accept
     * "whatever the agent has" must accept it too. Empty when the handle is unknown, the job is not
     * completed, or nothing was persisted yet (video jobs before their first download).
     */
    public Optional<FileRecord> fileRecordByHandle(String mediaId) {
        if (!GatewayMediaHandle.isHandle(mediaId)) return Optional.empty();
        try {
            var handle = GatewayMediaHandle.decode(mediaId);
            var job = mediaJobStore.get(handle.jobId()).orElse(null);
            if (job == null || !"completed".equals(job.state)) return Optional.empty();
            return fileRecord(job);
        } catch (BadRequestException e) {
            return Optional.empty();
        }
    }

    public Optional<FileRecord> fileRecord(MediaJob job) {
        if (!hasText(job.fileId)) return Optional.empty();
        try {
            return Optional.of(fileService.get(job.fileId));
        } catch (NotFoundException e) {
            LOGGER.warn("media job file record missing, job={}, fileId={}", job.id, job.fileId);
            return Optional.empty();
        }
    }

    /**
     * Caches downloaded bytes into a file record so a video reference does not re-download on every
     * use: video jobs never populate {@code fileId} until someone asks for the content.
     */
    public FileRecord storeBytes(MediaJob job, byte[] bytes, String contentType) {
        return artifactStore.storeBytes(job, bytes, contentType);
    }

    public byte[] bytes(FileRecord record) {
        return fileService.getBytes(record);
    }

    public String downloadUrl(FileRecord record) {
        return fileService.downloadUrl(record);
    }

    public MediaJobList list(int offset, int limit, String mediaType, String costSource, String userId) {
        String type = null;
        if (hasText(mediaType)) {
            type = mediaType.trim().toLowerCase(Locale.ROOT);
            if (!"image".equals(type) && !"video".equals(type)) {
                throw new BadRequestException("mediaType must be image or video");
            }
        }
        String source = null;
        if (hasText(costSource)) {
            source = costSource.trim().toLowerCase(Locale.ROOT);
            if (!COST_SOURCES.contains(source)) {
                throw new BadRequestException("costSource must be one of " + COST_SOURCES);
            }
        }
        var page = mediaJobStore.list(type, source, hasText(userId) ? userId.trim() : null, offset, limit);
        return new MediaJobList(page.total(), page.jobs());
    }

    public void updateVideoStatus(MediaJob job, VideoStatusResponse status) {
        var now = ZonedDateTime.now();
        var state = normalizeState(status.status());
        var completed = job.completedAt == null && "completed".equals(state);
        job.state = state;
        job.progress = status.progress();
        job.error = status.error();
        job.updatedAt = now;
        if (status.creditsConsumed() != null) job.creditsConsumed = status.creditsConsumed();
        MediaPricingService.MediaPrice settled = null;
        if (completed) {
            job.completedAt = now;
            if (!hasText(job.fileId)) job.artifactState = "pending";
            settled = settlePrice(job, status);
            if (settled != null) applyPriceToJob(job, settled);
        }
        mediaJobStore.replace(job);
        // only a settled generation is reported: an unsettled one would misreport its cost
        if (settled != null) MediaGenerationTrace.record(telemetryConfig, job);
    }

    private void applyPriceToJob(MediaJob job, MediaPricingService.MediaPrice price) {
        job.costSource = price.source();
        job.pricingModelId = price.pricingModelId();
        if (price.costUsd() != null) job.costUsd = price.costUsd();
        if (price.units() != null) job.mediaUnits = price.units();
        if (price.unitType() != null) job.mediaUnitType = price.unitType();
    }

    private MediaPricingService.MediaPrice settlePrice(MediaJob job, VideoStatusResponse status) {
        try {
            return costSettler.settleVideo(job, status);
        } catch (RuntimeException e) {
            LOGGER.warn("video cost settlement failed, job={}", job.id, e);
            return null;
        }
    }

    private String normalizeState(String value) {
        if (value == null || value.isBlank()) return "processing";
        return switch (value.toLowerCase(Locale.getDefault())) {
            case "completed", "succeeded", "success" -> "completed";
            case "failed", "error" -> "failed";
            case "cancelled", "canceled" -> "cancelled";
            case "queued", "pending" -> "queued";
            default -> "processing";
        };
    }

    public record MediaJobList(long total, List<MediaJob> jobs) {
    }

    /**
     * Upstream identity of a submitted video generation: the upstream video the job settles against, its parent
     * job when the request edits an existing video, and the parameters that drive its pricing and trace payload.
     */
    public record VideoJobSubmission(String upstreamVideoId, String parentJobId, Integer requestedSeconds, String prompt,
                                     String requestedSize, List<MediaJobInput> inputs) {
    }

    /**
     * Everything one image generation is recorded with: the request that carries the parameters, the settled
     * price, the upstream response holding the artifact, and the input assets that were sent with it.
     */
    public record ImageJobSubmission(ImageGenerationRequest request, MediaPricingService.MediaPrice price,
                                     ImageGenerationResponse response, List<MediaJobInput> inputs) {
    }
}

