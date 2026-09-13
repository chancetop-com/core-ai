package ai.core.server.gateway;

import ai.core.server.domain.MediaJob;
import ai.core.telemetry.TelemetryConfig;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import static ai.core.server.gateway.GatewaySupport.hasText;

/**
 * Emits one standalone trace per settled media generation, so image/video cost is auditable per generation
 * and flows into the trace-based cost statistics (daily analytics, by-model/provider dashboards) next to LLM cost.
 * Media is priced by its own pipeline (provider credits, per-second, per-image) rather than by tokens, so the
 * span carries the settled cost and its provenance as authoritative attributes. The trace payload carries the
 * generation prompt and a content link to the produced artifact, so the trace is as self-contained as an LLM call.
 *
 * @author stephen
 */
final class MediaGenerationTrace {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaGenerationTrace.class);
    private static final AttributeKey<String> LANGFUSE_INPUT = AttributeKey.stringKey("langfuse.observation.input");
    private static final AttributeKey<String> LANGFUSE_OUTPUT = AttributeKey.stringKey("langfuse.observation.output");
    private static final AttributeKey<String> GEN_AI_OPERATION_NAME = AttributeKey.stringKey("gen_ai.operation.name");
    private static final AttributeKey<String> GEN_AI_REQUEST_MODEL = AttributeKey.stringKey("gen_ai.request.model");
    private static final AttributeKey<Double> GEN_AI_COST_USD = AttributeKey.doubleKey("gen_ai.usage.cost_usd");
    private static final AttributeKey<String> GEN_AI_COST_SOURCE = AttributeKey.stringKey("gen_ai.usage.cost_source");
    private static final AttributeKey<String> GEN_AI_PRICING_MODEL_ID = AttributeKey.stringKey("gen_ai.usage.pricing_model_id");
    private static final AttributeKey<String> MEDIA_TYPE = AttributeKey.stringKey("media.type");
    private static final AttributeKey<String> MEDIA_RESOLVED_MODEL = AttributeKey.stringKey("media.resolved_model");
    private static final AttributeKey<Double> MEDIA_UNITS = AttributeKey.doubleKey("media.units");
    private static final AttributeKey<String> MEDIA_UNIT_TYPE = AttributeKey.stringKey("media.unit_type");
    private static final AttributeKey<String> MEDIA_PROVIDER_ID = AttributeKey.stringKey("media.provider_id");
    private static final AttributeKey<String> MEDIA_JOB_ID = AttributeKey.stringKey("media.job_id");
    private static final AttributeKey<String> USER_ID = AttributeKey.stringKey("user.id");
    private static final AttributeKey<String> SESSION_ID = AttributeKey.stringKey("session.id");

    static void record(TelemetryConfig telemetry, MediaJob job) {
        if (telemetry == null || !telemetry.isEnabled() || job == null) return;
        try {
            var span = start(telemetry, job);
            span.end(job.completedAt != null ? job.completedAt.toInstant() : Instant.now());
        } catch (RuntimeException e) {
            LOGGER.warn("media generation trace failed, mediaJobId={}", job.id, e);
        }
    }

    private static Span start(TelemetryConfig telemetry, MediaJob job) {
        var builder = telemetry.getOpenTelemetry().getTracer("core-ai-server")
            .spanBuilder("video".equals(job.mediaType) ? "Video generation" : "Image generation")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute(GEN_AI_OPERATION_NAME, "video".equals(job.mediaType) ? "video_generation" : "image_generation")
            .setAttribute(MEDIA_TYPE, job.mediaType != null ? job.mediaType : "media");
        applyNullableAttributes(builder, job);
        if (job.createdAt != null) builder.setStartTimestamp(job.createdAt.toInstant());
        return builder.startSpan();
    }

    private static void applyNullableAttributes(SpanBuilder builder, MediaJob job) {
        var model = job.requestedModel != null && !job.requestedModel.isBlank() ? job.requestedModel : job.resolvedModel;
        if (model != null) builder.setAttribute(GEN_AI_REQUEST_MODEL, model);
        if (job.resolvedModel != null) builder.setAttribute(MEDIA_RESOLVED_MODEL, job.resolvedModel);
        if (job.providerId != null) builder.setAttribute(MEDIA_PROVIDER_ID, job.providerId);
        if (job.id != null) builder.setAttribute(MEDIA_JOB_ID, job.id);
        if (job.userId != null) builder.setAttribute(USER_ID, job.userId);
        if (job.sessionId != null) builder.setAttribute(SESSION_ID, job.sessionId);
        if (job.mediaUnits != null) builder.setAttribute(MEDIA_UNITS, job.mediaUnits);
        if (job.mediaUnitType != null) builder.setAttribute(MEDIA_UNIT_TYPE, job.mediaUnitType);
        if (job.costUsd != null) builder.setAttribute(GEN_AI_COST_USD, job.costUsd);
        if (job.costSource != null) builder.setAttribute(GEN_AI_COST_SOURCE, job.costSource);
        if (job.pricingModelId != null) builder.setAttribute(GEN_AI_PRICING_MODEL_ID, job.pricingModelId);
        if (hasText(job.prompt)) builder.setAttribute(LANGFUSE_INPUT, job.prompt);
        var contentUrl = contentUrl(job);
        if (contentUrl != null) builder.setAttribute(LANGFUSE_OUTPUT, contentUrl);
    }

    /**
     * Platform link to the generated artifact, the same one the generations UI opens: stored bytes are served
     * from their file record, a video is streamed from the producing provider on demand. An image whose bytes
     * were never stored has nothing to serve, so it reports no output instead of a link that would 404.
     */
    private static String contentUrl(MediaJob job) {
        if (job.id == null) return null;
        if (hasText(job.fileId)) return "/api/files/" + job.fileId + "/content";
        return "image".equals(job.mediaType) ? null : "/api/media-jobs/" + job.id + "/content";
    }
}
