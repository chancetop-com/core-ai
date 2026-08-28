package ai.core.server.replay.service;

import ai.core.api.server.replay.CreateReplayRunRequest;
import ai.core.server.replay.domain.ReplayExperiment;
import ai.core.server.replay.domain.ReplayExperimentOrigin;
import ai.core.server.replay.domain.ReplayRun;
import ai.core.server.replay.domain.ReplayRunStatus;
import ai.core.server.replay.domain.ReplaySample;
import ai.core.server.replay.domain.ReplaySampleStatus;
import ai.core.server.replay.domain.ReplayUsage;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.SpanType;
import ai.core.server.trace.service.TraceAccessControl;
import ai.core.server.trace.service.TraceService;
import ai.core.utils.JsonUtil;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.bson.conversions.Bson;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Replay experiment lifecycle: snapshot creation, CRUD, draft/note persistence,
 * run submission and run queries. Execution itself is delegated to
 * {@link ReplayExecutor}.
 *
 * @author stephen
 */
public class ReplayService {
    static final int MAX_REQUEST_BYTES = 4 * 1024 * 1024;
    static final int MAX_TRACE_SNAPSHOT_SPANS = 2000;
    static final int MAX_SAMPLE_COUNT = 5;
    static final int BLANK_RETENTION_DAYS = 7;
    public static final int MAX_LIST_LIMIT = 200;
    static final String BLANK_DRAFT = "{\"messages\":[{\"role\":\"user\",\"content\":\"\"}]}";
    private static final String MODEL_PARAMETERS_ATTR = "langfuse.observation.model.parameters";
    private static final Set<String> PAYLOAD_ATTR_KEYS = Set.of(
            "langfuse.observation.input", "langfuse.observation.output", "gen_ai.prompt", "gen_ai.completion");

    private static boolean isChatML(String json) {
        try {
            var parsed = JsonUtil.toMap(json);
            return parsed.get("messages") instanceof List<?>;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ReplayUsage toUsage(Span span) {
        var usage = new ReplayUsage();
        usage.inputTokens = span.inputTokens;
        usage.outputTokens = span.outputTokens;
        usage.cachedTokens = span.cachedTokens;
        usage.costUsd = span.costUsd;
        usage.durationMs = span.durationMs;
        return usage;
    }

    private static Map<String, Object> snapshotRow(Span span) {
        var row = new LinkedHashMap<String, Object>();
        row.put("span_id", span.spanId);
        row.put("parent_span_id", span.parentSpanId);
        row.put("name", span.name);
        row.put("type", span.type != null ? span.type.name() : null);
        row.put("model", span.model);
        row.put("status", span.status != null ? span.status.name() : null);
        row.put("error_message", span.errorMessage);
        row.put("duration_ms", span.durationMs);
        row.put("started_at", span.startedAt);
        row.put("completed_at", span.completedAt);
        row.put("input_tokens", span.inputTokens);
        row.put("output_tokens", span.outputTokens);
        row.put("cost_usd", span.costUsd);
        if (span.attributes != null) {
            var attrs = new LinkedHashMap<>(span.attributes);
            PAYLOAD_ATTR_KEYS.forEach(attrs::remove);
            row.put("attributes", attrs);
        }
        return row;
    }

    @Inject
    MongoCollection<ReplayExperiment> experimentCollection;
    @Inject
    MongoCollection<ReplayRun> runCollection;
    @Inject
    TraceService traceService;
    @Inject
    TraceAccessControl traceAccessControl;
    @Inject
    ReplayExecutor replayExecutor;

    public ReplayExperiment create(String userId, boolean admin, String traceId, String spanId) {
        var now = ZonedDateTime.now();
        var experiment = new ReplayExperiment();
        experiment.id = UUID.randomUUID().toString();
        experiment.userId = userId;
        experiment.runCount = 0;
        experiment.createdAt = now;
        experiment.updatedAt = now;
        // Blank (playground) experiment: the user writes the request from scratch,
        // no source span, no snapshots, no pinned original response. Creation is an
        // explicit user action (the "New Playground" button), so no dedup here.
        if (isBlank(traceId) && isBlank(spanId)) {
            experiment.origin = ReplayExperimentOrigin.BLANK;
            experiment.draftRequest = BLANK_DRAFT;
            experimentCollection.insert(experiment);
            return experiment;
        }
        if (isBlank(traceId) || isBlank(spanId)) {
            throw new BadRequestException("both trace_id and span_id are required, or neither for a blank experiment");
        }
        experiment.origin = ReplayExperimentOrigin.SPAN;

        var trace = traceService.get(traceId);
        if (trace == null) throw new NotFoundException("trace not found: " + traceId);
        if (!traceAccessControl.canRead(trace, userId, admin)) throw new NotFoundException("trace not found: " + traceId);
        var span = traceService.span(trace.traceId, spanId);
        if (span == null) throw new NotFoundException("span not found: " + spanId);
        if (span.type != SpanType.LLM) throw new BadRequestException("replay requires an LLM span, got type=" + span.type);
        if (span.input == null || span.input.isBlank() || !isChatML(span.input)) {
            throw new BadRequestException("span input is not a ChatML request, replay unsupported");
        }
        if (span.input.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
            throw new BadRequestException("span input exceeds " + MAX_REQUEST_BYTES + " bytes, replay unsupported");
        }

        experiment.traceId = trace.traceId;
        experiment.spanId = span.spanId;
        experiment.agentId = trace.agentId;
        experiment.agentName = trace.agentName;
        experiment.sessionId = trace.sessionId;
        experiment.traceSource = trace.source;
        experiment.spanName = span.name;
        experiment.originalModel = span.model;
        experiment.originalInput = span.input;
        experiment.originalOutput = span.output;
        experiment.originalParams = span.attributes != null ? span.attributes.get(MODEL_PARAMETERS_ATTR) : null;
        experiment.originalUsage = toUsage(span);
        experiment.traceSnapshot = buildTraceSnapshot(trace.traceId);
        experimentCollection.insert(experiment);
        return experiment;
    }

    public List<ReplayExperiment> list(String userId, boolean admin, String agentId, ReplayExperimentOrigin origin, int offset, int limit) {
        var query = new Query();
        query.filter = listFilter(userId, admin, agentId, origin);
        query.sort = Sorts.descending("created_at");
        query.skip = Math.max(offset, 0);
        query.limit = Math.clamp(limit, 1, MAX_LIST_LIMIT);
        return experimentCollection.find(query);
    }

    public long count(String userId, boolean admin, String agentId, ReplayExperimentOrigin origin) {
        return experimentCollection.count(listFilter(userId, admin, agentId, origin));
    }

    public ReplayExperiment get(String id) {
        return experimentCollection.get(id).orElse(null);
    }

    /**
     * Patch semantics: null means "leave unchanged" so the draft autosave and the
     * note save can each send only their own field without wiping the other.
     */
    public ReplayExperiment update(String id, String userId, boolean admin, String draftRequest, String note) {
        var experiment = requireOwned(id, userId, admin);
        if (draftRequest != null) experiment.draftRequest = draftRequest;
        if (note != null) experiment.note = note;
        experiment.updatedAt = ZonedDateTime.now();
        experimentCollection.replace(experiment);
        return experiment;
    }

    public void delete(String id, String userId, boolean admin) {
        var experiment = requireOwned(id, userId, admin);
        runCollection.delete(Filters.eq("experiment_id", experiment.id));
        experimentCollection.delete(Filters.eq("_id", experiment.id));
    }

    public ReplayRun createRun(String experimentId, String userId, boolean admin, CreateReplayRunRequest request) {
        var experiment = requireOwned(experimentId, userId, admin);
        if (request.request == null || request.request.isBlank() || !isChatML(request.request)) {
            throw new BadRequestException("request must be a ChatML JSON with a messages array");
        }
        if (request.request.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
            throw new BadRequestException("request exceeds " + MAX_REQUEST_BYTES + " bytes");
        }
        int sampleCount = request.sampleCount != null ? request.sampleCount : 1;
        if (sampleCount < 1 || sampleCount > MAX_SAMPLE_COUNT) {
            throw new BadRequestException("sample_count must be between 1 and " + MAX_SAMPLE_COUNT);
        }
        // Fail fast on unparseable request JSON before creating any record.
        try {
            ReplayRequestCodec.parse(request.request);
        } catch (RuntimeException e) {
            throw new BadRequestException("request is not valid ChatML JSON: " + e.getMessage(), "BAD_REQUEST", e);
        }

        var now = ZonedDateTime.now();
        var run = new ReplayRun();
        run.id = UUID.randomUUID().toString();
        run.experimentId = experiment.id;
        run.userId = userId;
        run.label = request.label;
        run.request = request.request;
        run.model = blankToNull(request.model);
        run.temperature = request.temperature;
        run.reasoningEffort = blankToNull(request.reasoningEffort);
        run.sampleCount = sampleCount;
        run.samples = new ArrayList<>(sampleCount);
        for (int i = 0; i < sampleCount; i++) {
            var sample = new ReplaySample();
            sample.index = i;
            sample.status = ReplaySampleStatus.RUNNING;
            run.samples.add(sample);
        }
        run.status = ReplayRunStatus.RUNNING;
        run.createdAt = now;
        runCollection.insert(run);

        experimentCollection.update(Filters.eq("_id", experiment.id),
                Updates.combine(Updates.inc("run_count", 1), Updates.set("updated_at", now)));
        replayExecutor.submit(run);
        return run;
    }

    public ReplayRun getRun(String runId) {
        return runCollection.get(runId).orElse(null);
    }

    public void cancelRun(String runId, String userId, boolean admin) {
        var run = runCollection.get(runId).orElse(null);
        if (run == null) throw new NotFoundException("replay run not found");
        if (!admin && !userId.equals(run.userId)) throw new NotFoundException("replay run not found");
        replayExecutor.cancel(runId);
    }

    /**
     * Sweeps blank experiments that were explicitly created but never touched
     * (no run, no note, draft still the starter template) past the retention window.
     */
    public long cleanupAbandonedBlankExperiments() {
        var cutoff = ZonedDateTime.now().minusDays(BLANK_RETENTION_DAYS);
        return experimentCollection.delete(Filters.and(
                untouchedBlankFilter(),
                Filters.lt("created_at", cutoff)));
    }

    private Bson untouchedBlankFilter() {
        return Filters.and(
                Filters.eq("origin", "blank"),
                Filters.eq("run_count", 0),
                Filters.eq("note", null),
                Filters.or(Filters.eq("draft_request", null), Filters.eq("draft_request", BLANK_DRAFT)));
    }

    /** Summary rows for one experiment, excluding the heavy request/samples payloads. */
    public List<ReplayRun> runSummaries(String experimentId) {
        var query = new Query();
        query.filter = Filters.eq("experiment_id", experimentId);
        query.sort = Sorts.descending("created_at");
        query.projection = Projections.exclude("request", "samples");
        return runCollection.find(query);
    }

    private Bson listFilter(String userId, boolean admin, String agentId, ReplayExperimentOrigin origin) {
        var filters = new ArrayList<Bson>();
        if (!admin) filters.add(Filters.eq("user_id", userId));
        if (agentId != null && !agentId.isBlank()) filters.add(Filters.eq("agent_id", agentId));
        // origin equality is index-backed via (origin, created_at); trace_id presence
        // checks ($ne null / $eq null) have no index path and fail 291 on admin counts
        if (origin == ReplayExperimentOrigin.SPAN) filters.add(Filters.eq("origin", "span"));
        if (origin == ReplayExperimentOrigin.BLANK) filters.add(Filters.eq("origin", "blank"));
        if (filters.isEmpty()) return Filters.empty();
        return filters.size() == 1 ? filters.getFirst() : Filters.and(filters);
    }

    private ReplayExperiment requireOwned(String id, String userId, boolean admin) {
        var experiment = experimentCollection.get(id).orElse(null);
        if (experiment == null || !admin && !userId.equals(experiment.userId)) {
            throw new NotFoundException("replay experiment not found");
        }
        return experiment;
    }

    private String buildTraceSnapshot(String traceId) {
        var spans = traceService.spans(traceId);
        var truncated = spans.size() > MAX_TRACE_SNAPSHOT_SPANS;
        var rows = new ArrayList<Map<String, Object>>();
        for (var span : truncated ? spans.subList(0, MAX_TRACE_SNAPSHOT_SPANS) : spans) {
            rows.add(snapshotRow(span));
        }
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("spans", rows);
        snapshot.put("truncated", truncated);
        return JsonUtil.toJson(snapshot);
    }
}
