package ai.core.server.replay.service;

import ai.core.api.server.replay.CreateReplayRunRequest;
import ai.core.server.replay.domain.ReplayExperiment;
import ai.core.server.replay.domain.ReplayExperimentOrigin;
import ai.core.server.replay.domain.ReplayRun;
import ai.core.server.replay.domain.ReplayRunStatus;
import ai.core.server.replay.domain.ReplaySampleStatus;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.SpanStatus;
import ai.core.server.trace.domain.SpanType;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.service.TraceAccessControl;
import ai.core.server.trace.service.TraceService;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Replay experiment lifecycle: snapshot creation, validation guards, ownership
 * rules and run submission.
 *
 * @author stephen
 */
class ReplayServiceTest {
    private static final String CHATML_INPUT = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

    private static Trace trace(String traceId, String userId, String agentId, String agentName, String source) {
        var trace = new Trace();
        trace.id = "doc-" + traceId;
        trace.traceId = traceId;
        trace.userId = userId;
        trace.agentId = agentId;
        trace.agentName = agentName;
        trace.source = source;
        trace.sessionId = "session-1";
        return trace;
    }

    private static Span llmSpan(String spanId, String traceId, String name) {
        var span = new Span();
        span.id = "doc-" + spanId;
        span.spanId = spanId;
        span.traceId = traceId;
        span.name = name;
        span.type = SpanType.LLM;
        span.model = "gpt-5-mini";
        span.input = CHATML_INPUT;
        span.output = "{\"content\":\"hello\",\"role\":\"assistant\"}";
        span.attributes = Map.of(
                "langfuse.observation.model.parameters", "{\"temperature\":0.7}",
                "client.type", "chat");
        span.inputTokens = 10L;
        span.outputTokens = 5L;
        span.cachedTokens = 0L;
        span.costUsd = 0.001D;
        span.durationMs = 250L;
        span.status = SpanStatus.OK;
        return span;
    }

    private static ReplayExperiment experiment(String id, String userId) {
        var experiment = new ReplayExperiment();
        experiment.id = id;
        experiment.userId = userId;
        return experiment;
    }

    private static ReplayRun run(String id, String experimentId) {
        var run = new ReplayRun();
        run.id = id;
        run.experimentId = experimentId;
        run.userId = "user-1";
        return run;
    }

    @SuppressWarnings("unchecked")
    private final MongoCollection<ReplayExperiment> experimentCollection = mock(MongoCollection.class);
    @SuppressWarnings("unchecked")
    private final MongoCollection<ReplayRun> runCollection = mock(MongoCollection.class);
    private final TraceService traceService = mock(TraceService.class);
    private final TraceAccessControl traceAccessControl = mock(TraceAccessControl.class);
    private final ReplayExecutor replayExecutor = mock(ReplayExecutor.class);
    private final ReplayService service;

    ReplayServiceTest() {
        service = new ReplayService();
        service.experimentCollection = experimentCollection;
        service.runCollection = runCollection;
        service.traceService = traceService;
        service.traceAccessControl = traceAccessControl;
        service.replayExecutor = replayExecutor;
    }

    @Test
    void createSnapshotsPayloadAndTraceContext() {
        var trace = trace("trace-1", "user-1", "agent-1", "My Agent", "chat");
        var span = llmSpan("span-1", "trace-1", "my-llm-call");
        when(traceService.get("trace-1")).thenReturn(trace);
        when(traceService.span("trace-1", "span-1")).thenReturn(span);
        when(traceService.spans("trace-1")).thenReturn(List.of(span));
        when(traceAccessControl.canRead(any(Trace.class), any(), anyBoolean())).thenReturn(Boolean.TRUE);

        var experiment = service.create("user-1", false, "trace-1", "span-1");

        assertEquals("user-1", experiment.userId);
        assertEquals(ReplayExperimentOrigin.SPAN, experiment.origin);
        assertEquals("trace-1", experiment.traceId);
        assertEquals("agent-1", experiment.agentId);
        assertEquals("My Agent", experiment.agentName);
        assertEquals("chat", experiment.traceSource);
        assertEquals("my-llm-call", experiment.spanName);
        assertEquals("gpt-5-mini", experiment.originalModel);
        assertEquals(CHATML_INPUT, experiment.originalInput);
        assertEquals("{\"content\":\"hello\",\"role\":\"assistant\"}", experiment.originalOutput);
        assertEquals("{\"temperature\":0.7}", experiment.originalParams);
        assertNotNull(experiment.originalUsage);
        assertEquals(10L, experiment.originalUsage.inputTokens);
        assertEquals(5L, experiment.originalUsage.outputTokens);
        assertEquals(0.001D, experiment.originalUsage.costUsd);
        assertTrue(experiment.traceSnapshot.contains("span-1"));
        assertEquals(0, experiment.runCount);
        verify(experimentCollection).insert(experiment);
    }

    @Test
    void createRejectsNonLlmSpan() {
        var trace = trace("trace-1", "user-1", "agent-1", "My Agent", "chat");
        var span = llmSpan("span-1", "trace-1", "tool-call");
        span.type = SpanType.TOOL;
        when(traceService.get("trace-1")).thenReturn(trace);
        when(traceService.span("trace-1", "span-1")).thenReturn(span);
        when(traceAccessControl.canRead(any(Trace.class), any(), anyBoolean())).thenReturn(Boolean.TRUE);

        assertThrows(BadRequestException.class, () -> service.create("user-1", false, "trace-1", "span-1"));
        verify(experimentCollection, never()).insert(any(ReplayExperiment.class));
    }

    @Test
    void createRejectsNonChatMLInput() {
        var trace = trace("trace-1", "user-1", "agent-1", "My Agent", "chat");
        var span = llmSpan("span-1", "trace-1", "my-llm-call");
        span.input = "plain text, not json";
        when(traceService.get("trace-1")).thenReturn(trace);
        when(traceService.span("trace-1", "span-1")).thenReturn(span);
        when(traceAccessControl.canRead(any(Trace.class), any(), anyBoolean())).thenReturn(Boolean.TRUE);

        assertThrows(BadRequestException.class, () -> service.create("user-1", false, "trace-1", "span-1"));
    }

    @Test
    void createRejectsOversizedInput() {
        var trace = trace("trace-1", "user-1", "agent-1", "My Agent", "chat");
        var span = llmSpan("span-1", "trace-1", "my-llm-call");
        span.input = "{\"messages\":[" + "x".repeat(ReplayService.MAX_REQUEST_BYTES) + "]}";
        when(traceService.get("trace-1")).thenReturn(trace);
        when(traceService.span("trace-1", "span-1")).thenReturn(span);
        when(traceAccessControl.canRead(any(Trace.class), any(), anyBoolean())).thenReturn(Boolean.TRUE);

        assertThrows(BadRequestException.class, () -> service.create("user-1", false, "trace-1", "span-1"));
    }

    @Test
    void createBlankExperimentWithoutSpan() {
        var experiment = service.create("user-1", false, null, null);

        assertEquals("user-1", experiment.userId);
        assertEquals(ReplayExperimentOrigin.BLANK, experiment.origin);
        assertNull(experiment.traceId);
        assertNull(experiment.originalInput);
        assertNull(experiment.traceSnapshot);
        assertEquals(ReplayService.BLANK_DRAFT, experiment.draftRequest);
        assertEquals(0, experiment.runCount);
        verify(experimentCollection).insert(experiment);
        verify(traceService, never()).get(any());
    }

    @Test
    void listFiltersByOrigin() {
        when(experimentCollection.find(any(Query.class))).thenReturn(List.of());

        service.list("user-1", false, null, ReplayExperimentOrigin.BLANK, 0, 20);

        var captor = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(experimentCollection).find(captor.capture());
        assertTrue(captor.getValue().filter.toString().contains("trace_id"));
    }

    @Test
    void createRejectsPartialSpanReference() {
        assertThrows(BadRequestException.class, () -> service.create("user-1", false, "trace-1", null));
        assertThrows(BadRequestException.class, () -> service.create("user-1", false, null, "span-1"));
        verify(experimentCollection, never()).insert(any(ReplayExperiment.class));
    }

    @Test
    void createRequiresTraceReadAccess() {
        var trace = trace("trace-1", "user-1", "agent-1", "My Agent", "chat");
        when(traceService.get("trace-1")).thenReturn(trace);
        when(traceAccessControl.canRead(any(Trace.class), any(), anyBoolean())).thenReturn(Boolean.FALSE);

        assertThrows(NotFoundException.class, () -> service.create("user-2", false, "trace-1", "span-1"));
        verify(traceService, never()).span(any(), any());
    }

    @Test
    void createRunValidatesSampleCountAndRequest() {
        var experiment = experiment("exp-1", "user-1");
        when(experimentCollection.get("exp-1")).thenReturn(Optional.of(experiment));

        var badCount = new CreateReplayRunRequest();
        badCount.sampleCount = 6;
        badCount.request = CHATML_INPUT;
        assertThrows(BadRequestException.class, () -> service.createRun("exp-1", "user-1", false, badCount));

        var badJson = new CreateReplayRunRequest();
        badJson.sampleCount = 1;
        badJson.request = "not json";
        assertThrows(BadRequestException.class, () -> service.createRun("exp-1", "user-1", false, badJson));

        verify(runCollection, never()).insert(any(ReplayRun.class));
        verify(replayExecutor, never()).submit(any(ReplayRun.class));
    }

    @Test
    void createRunInsertsRunAndSubmitsToExecutor() {
        var experiment = experiment("exp-1", "user-1");
        when(experimentCollection.get("exp-1")).thenReturn(Optional.of(experiment));

        var request = new CreateReplayRunRequest();
        request.request = CHATML_INPUT;
        request.model = "gpt-5.1";
        request.temperature = 0.3;
        request.reasoningEffort = "high";
        request.sampleCount = 3;
        request.label = "shortened system prompt";

        var run = service.createRun("exp-1", "user-1", false, request);

        assertNotNull(run.id);
        assertEquals("exp-1", run.experimentId);
        assertEquals("gpt-5.1", run.model);
        assertEquals(0.3D, run.temperature);
        assertEquals("high", run.reasoningEffort);
        assertEquals("shortened system prompt", run.label);
        assertEquals(3, run.sampleCount);
        assertEquals(3, run.samples.size());
        assertTrue(run.samples.stream().allMatch(sample -> sample.status == ReplaySampleStatus.RUNNING));
        assertEquals(ReplayRunStatus.RUNNING, run.status);
        verify(runCollection).insert(run);
        verify(replayExecutor).submit(run);
        verify(experimentCollection).update(any(), any());
    }

    @Test
    void createRunRejectsOtherUsersExperiment() {
        var experiment = experiment("exp-1", "other-user");
        when(experimentCollection.get("exp-1")).thenReturn(Optional.of(experiment));

        var request = new CreateReplayRunRequest();
        request.request = CHATML_INPUT;
        request.sampleCount = 1;

        assertThrows(NotFoundException.class, () -> service.createRun("exp-1", "user-1", false, request));
    }

    @Test
    void adminCanOperateOnAnyExperiment() {
        var experiment = experiment("exp-1", "other-user");
        when(experimentCollection.get("exp-1")).thenReturn(Optional.of(experiment));

        var updated = service.update("exp-1", "admin-1", true, "{\"messages\":[]}", "note");
        assertEquals("{\"messages\":[]}", updated.draftRequest);
        assertEquals("note", updated.note);
        verify(experimentCollection).replace(experiment);
    }

    @Test
    void updatePatchesOnlyProvidedFields() {
        var experiment = experiment("exp-1", "user-1");
        experiment.draftRequest = "{\"messages\":[]}";
        experiment.note = "old note";
        when(experimentCollection.get("exp-1")).thenReturn(Optional.of(experiment));

        var afterNoteSave = service.update("exp-1", "user-1", false, null, "new note");
        assertEquals("{\"messages\":[]}", afterNoteSave.draftRequest);
        assertEquals("new note", afterNoteSave.note);

        var afterDraftSave = service.update("exp-1", "user-1", false, CHATML_INPUT, null);
        assertEquals(CHATML_INPUT, afterDraftSave.draftRequest);
        assertEquals("new note", afterDraftSave.note);
    }

    @Test
    void cleanupReturnsDeletedCount() {
        when(experimentCollection.delete(any(org.bson.conversions.Bson.class))).thenReturn(3L);

        assertEquals(3L, service.cleanupAbandonedBlankExperiments());

        verify(experimentCollection).delete(any(org.bson.conversions.Bson.class));
    }

    @Test
    void nonAdminCannotUpdateOthersExperiment() {
        var experiment = experiment("exp-1", "other-user");
        when(experimentCollection.get("exp-1")).thenReturn(Optional.of(experiment));

        assertThrows(NotFoundException.class, () -> service.update("exp-1", "user-1", false, null, null));
    }

    @Test
    void deleteCascadesToRuns() {
        var experiment = experiment("exp-1", "user-1");
        when(experimentCollection.get("exp-1")).thenReturn(Optional.of(experiment));

        service.delete("exp-1", "user-1", false);

        verify(runCollection).delete(any());
        verify(experimentCollection).delete(any());
    }

    @Test
    void getRunSummaryExcludesHeavyPayloads() {
        when(runCollection.find(any(Query.class))).thenReturn(List.of(run("run-1", "exp-1")));

        var runs = service.runSummaries("exp-1");

        assertEquals(1, runs.size());
        assertEquals("run-1", runs.getFirst().id);
    }

    @Test
    void cancelRunRequiresOwner() {
        when(runCollection.get("run-1")).thenReturn(Optional.of(run("run-1", "exp-1")));

        assertThrows(NotFoundException.class, () -> service.cancelRun("run-1", "user-2", false));
        verify(replayExecutor, never()).cancel(any());
    }

    @Test
    void cancelRunDelegatesToExecutor() {
        when(runCollection.get("run-1")).thenReturn(Optional.of(run("run-1", "exp-1")));

        service.cancelRun("run-1", "user-1", false);

        verify(replayExecutor).cancel("run-1");
    }
}
