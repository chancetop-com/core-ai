package ai.core.server.gateway;

import ai.core.media.domain.ImageData;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;
import ai.core.media.reference.HttpRemoteMediaLoader;
import ai.core.media.reference.MediaModality;
import ai.core.media.reference.RemoteMediaLoader;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.MediaJob;
import ai.core.server.file.FileService;
import ai.core.server.trace.service.MediaPricingService;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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
    MongoCollection<MediaJob> mediaJobCollection;
    @Inject
    MediaCostSettler costSettler;
    @Inject
    FileService fileService;

    // only external/upstream result URLs go through this; platform-owned bytes come from FileService
    RemoteMediaLoader remoteMediaLoader = new HttpRemoteMediaLoader();

    public MediaJob createVideoJob(MediaJobOwner owner, GatewayRoute route, String requestedModel, String upstreamVideoId) {
        return createVideoJob(owner, route, requestedModel, upstreamVideoId, null, null);
    }

    public MediaJob createVideoJob(MediaJobOwner owner, GatewayRoute route, String requestedModel, String upstreamVideoId, String parentJobId) {
        return createVideoJob(owner, route, requestedModel, upstreamVideoId, parentJobId, null);
    }

    public MediaJob createVideoJob(MediaJobOwner owner, GatewayRoute route, String requestedModel, String upstreamVideoId,
                                   String parentJobId, Integer requestedSeconds) {
        var jobOwner = owner == null ? MediaJobOwner.UNKNOWN : owner;
        var now = ZonedDateTime.now();
        var job = new MediaJob();
        job.id = UUID.randomUUID().toString();
        job.userId = jobOwner.userId();
        job.sessionId = jobOwner.sessionId();
        job.agentRunId = jobOwner.agentRunId();
        job.providerId = route.provider().id;
        job.upstreamVideoId = upstreamVideoId;
        job.parentJobId = parentJobId;
        job.requestedModel = requestedModel;
        job.resolvedModel = route.upstreamModel();
        job.state = "submitted";
        job.mediaType = "video";
        job.requestedSeconds = requestedSeconds;
        job.createdAt = now;
        job.updatedAt = now;
        mediaJobCollection.insert(job);
        return job;
    }

    public MediaJob createImageJob(MediaJobOwner owner, GatewayRoute route, String requestedModel,
                                   MediaPricingService.MediaPrice price, ImageGenerationResponse response) {
        var jobOwner = owner == null ? MediaJobOwner.UNKNOWN : owner;
        var now = ZonedDateTime.now();
        var job = new MediaJob();
        job.id = UUID.randomUUID().toString();
        job.userId = jobOwner.userId();
        job.sessionId = jobOwner.sessionId();
        job.agentRunId = jobOwner.agentRunId();
        job.providerId = route.provider().id;
        job.requestedModel = requestedModel;
        job.resolvedModel = route.upstreamModel();
        job.state = "completed";
        job.mediaType = "image";
        job.createdAt = now;
        job.updatedAt = now;
        job.completedAt = now;
        job.mediaUnits = price.units();
        job.mediaUnitType = price.unitType();
        job.costUsd = price.costUsd();
        job.costSource = price.source();
        job.pricingModelId = price.pricingModelId();
        if (response != null) {
            job.upstreamInteractionId = response.interactionId();
            var image = response.data() == null || response.data().isEmpty() ? null : response.data().getFirst();
            if (image != null && hasText(image.url())) job.upstreamAssetUrl = image.url();
            storeImage(job, image);
        }
        mediaJobCollection.insert(job);
        return job;
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
            var record = fileService.upload(job.userId, "generated-image" + FileService.extension(contentType), contentType, tempFile);
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
        return mediaJobCollection.get(jobId)
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
        var filters = new ArrayList<Bson>();
        filters.add(Filters.eq("state", "completed"));
        filters.add(mediaTypeFilter(modality));
        if (!hasText(jobOwner.sessionId()) && !hasText(jobOwner.userId())) return Optional.empty();
        if (hasText(jobOwner.sessionId())) filters.add(Filters.eq("session_id", jobOwner.sessionId()));
        if (hasText(jobOwner.userId())) filters.add(Filters.eq("user_id", jobOwner.userId()));
        var query = new Query();
        query.filter = Filters.and(filters);
        query.sort = Sorts.descending("completed_at");
        query.limit = 1;
        var jobs = mediaJobCollection.find(query);
        return jobs.isEmpty() ? Optional.empty() : Optional.of(jobs.getFirst());
    }

    private Bson mediaTypeFilter(MediaModality modality) {
        // legacy video jobs predate media_type; a missing field means video
        return modality == MediaModality.VIDEO
                ? Filters.in("media_type", Arrays.asList("video", null))
                : Filters.eq("media_type", "image");
    }

    /** Resolves a handle to a job the caller owns, with the failure the agent can act on. */
    public MediaJob resolveReference(String mediaId, MediaJobOwner owner) {
        var handle = GatewayMediaHandle.decode(mediaId);
        var job = mediaJobCollection.get(handle.jobId())
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
        try {
            var tempFile = Files.createTempFile("media-job-", ".bin");
            Files.write(tempFile, bytes);
            var type = hasText(contentType) ? contentType : "application/octet-stream";
            var record = fileService.uploadIfAbsent(job.userId, "media-" + job.id + FileService.extension(type), type, tempFile);
            mediaJobCollection.update(Filters.eq("_id", job.id), Updates.combine(
                    Updates.set("file_id", record.id),
                    Updates.set("file_name", record.fileName),
                    Updates.set("content_type", record.contentType),
                    Updates.set("updated_at", ZonedDateTime.now())));
            job.fileId = record.id;
            job.fileName = record.fileName;
            job.contentType = record.contentType;
            return record;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to cache media job bytes, job=" + job.id, e);
        }
    }

    public byte[] bytes(FileRecord record) {
        return fileService.getBytes(record);
    }

    public String downloadUrl(FileRecord record) {
        return fileService.downloadUrl(record);
    }

    public MediaJobList list(int offset, int limit, String mediaType, String costSource, String userId) {
        var filters = new ArrayList<Bson>();
        if (hasText(mediaType)) {
            var type = mediaType.trim().toLowerCase(Locale.ROOT);
            if (!"image".equals(type) && !"video".equals(type)) {
                throw new BadRequestException("mediaType must be image or video");
            }
            // legacy video jobs predate media_type; a missing field means video
            filters.add("video".equals(type) ? Filters.in("media_type", Arrays.asList("video", null)) : Filters.eq("media_type", "image"));
        }
        if (hasText(costSource)) {
            var source = costSource.trim().toLowerCase(Locale.ROOT);
            if (!COST_SOURCES.contains(source)) {
                throw new BadRequestException("costSource must be one of " + COST_SOURCES);
            }
            filters.add(Filters.eq("cost_source", source));
        }
        if (hasText(userId)) {
            filters.add(Filters.eq("user_id", userId.trim()));
        }
        var filter = filters.isEmpty() ? Filters.empty()
                : filters.size() == 1 ? filters.getFirst() : Filters.and(filters);
        var total = mediaJobCollection.count(filter);
        var query = new Query();
        query.filter = filter;
        query.sort = Sorts.descending("created_at");
        query.skip = offset;
        query.limit = limit;
        return new MediaJobList(total, mediaJobCollection.find(query));
    }

    public void updateVideoStatus(MediaJob job, VideoStatusResponse status) {
        var now = ZonedDateTime.now();
        var state = normalizeState(status.status());
        var completed = job.completedAt == null && "completed".equals(state);
        var updates = Updates.combine(
                Updates.set("state", state),
                Updates.set("progress", status.progress()),
                Updates.set("error", status.error()),
                Updates.set("updated_at", now));
        if (status.creditsConsumed() != null) {
            updates = Updates.combine(updates, Updates.set("credits_consumed", status.creditsConsumed()));
        }
        if (completed) {
            updates = Updates.combine(updates, Updates.set("completed_at", now));
            var price = settlePrice(job, status);
            updates = applyPrice(updates, price);
        }
        mediaJobCollection.update(Filters.eq("_id", job.id), updates);
        job.state = state;
        job.progress = status.progress();
        job.error = status.error();
        job.updatedAt = now;
        if (completed) job.completedAt = now;
    }

    private Bson applyPrice(Bson updates, MediaPricingService.MediaPrice price) {
        if (price == null || price.costUsd() == null) return updates;
        var priced = Updates.combine(updates,
                Updates.set("cost_usd", price.costUsd()),
                Updates.set("cost_source", price.source()),
                Updates.set("pricing_model_id", price.pricingModelId()));
        if (price.units() != null) priced = Updates.combine(priced, Updates.set("media_units", price.units()));
        if (price.unitType() != null) priced = Updates.combine(priced, Updates.set("media_unit_type", price.unitType()));
        return priced;
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
}
