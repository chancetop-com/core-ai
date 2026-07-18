package ai.core.server.gateway;

import ai.core.media.domain.VideoStatusResponse;
import ai.core.server.domain.MediaJob;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;

import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * @author Stephen
 */
public class MediaJobService {
    @Inject
    MongoCollection<MediaJob> mediaJobCollection;

    public MediaJob createVideoJob(MediaJobOwner owner, GatewayRoute route, String requestedModel, String upstreamVideoId) {
        var jobOwner = owner == null ? MediaJobOwner.UNKNOWN : owner;
        var now = ZonedDateTime.now();
        var job = new MediaJob();
        job.id = UUID.randomUUID().toString();
        job.userId = jobOwner.userId();
        job.sessionId = jobOwner.sessionId();
        job.agentRunId = jobOwner.agentRunId();
        job.providerId = route.provider().id;
        job.upstreamVideoId = upstreamVideoId;
        job.requestedModel = requestedModel;
        job.resolvedModel = route.upstreamModel();
        job.state = "submitted";
        job.createdAt = now;
        job.updatedAt = now;
        mediaJobCollection.insert(job);
        return job;
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

    public void updateVideoStatus(MediaJob job, VideoStatusResponse status) {
        var now = ZonedDateTime.now();
        var state = normalizeState(status.status());
        var completed = job.completedAt == null && "completed".equals(state);
        var updates = Updates.combine(
                Updates.set("state", state),
                Updates.set("progress", status.progress()),
                Updates.set("error", status.error()),
                Updates.set("updated_at", now));
        if (completed) {
            updates = Updates.combine(updates, Updates.set("completed_at", now));
        }
        mediaJobCollection.update(Filters.eq("_id", job.id), updates);
        job.state = state;
        job.progress = status.progress();
        job.error = status.error();
        job.updatedAt = now;
        if (completed) job.completedAt = now;
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
}
