package ai.core.server.trace.maintenance;

import ai.core.server.domain.AgentRun;
import ai.core.server.domain.RunStatus;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceStatus;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class StaleRunCleanupServiceTest {
    @Test
    void failsRunAndTraceLeftRunningByACrash() {
        var service = service();
        var started = ZonedDateTime.now().minusMinutes(45);
        var lastSpan = ZonedDateTime.now().minusMinutes(40);
        var trace = trace("t-1", TraceStatus.RUNNING, started, lastSpan);
        stubStaleTraceScan(service, trace);
        stubTraceLookup(service, trace);
        when(service.agentRunCollection.find(any(Query.class))).thenReturn(List.of(run("run-1", RunStatus.RUNNING, started, "t-1")));

        service.cleanup();

        var traceUpdate = ArgumentCaptor.forClass(Bson.class);
        verify(service.traceCollection).update(any(Bson.class), traceUpdate.capture());
        var traceSet = traceUpdate.getValue().toString();
        assertTrue(traceSet.contains("ERROR"), traceSet);
        assertTrue(traceSet.contains("interrupted"), traceSet);
        // duration reflects the last span, not the cleanup moment
        assertTrue(traceSet.contains("duration_ms"), traceSet);

        var runSet = capturedRunUpdate(service);
        assertTrue(runSet.contains("FAILED"), runSet);
        assertTrue(runSet.contains("interrupted"), runSet);
    }

    @Test
    void reconcilesRunFromFinishedTrace() {
        var service = service();
        var started = ZonedDateTime.now().minusMinutes(45);
        var finishedAt = ZonedDateTime.now().minusMinutes(35);
        var trace = trace("t-1", TraceStatus.COMPLETED, started, finishedAt);
        trace.completedAt = finishedAt;
        trace.output = "the answer";
        when(service.traceCollection.find(any(Query.class))).thenReturn(List.of());
        stubTraceLookup(service, trace);
        when(service.agentRunCollection.find(any(Query.class))).thenReturn(List.of(run("run-1", RunStatus.RUNNING, started, "t-1")));

        service.cleanup();

        var runSet = capturedRunUpdate(service);
        assertTrue(runSet.contains("COMPLETED"), runSet);
        assertTrue(runSet.contains("the answer"), runSet);
        verify(service.traceCollection, never()).update(any(Bson.class), any(Bson.class));
    }

    @Test
    void leavesLiveRunAlone() {
        var service = service();
        var started = ZonedDateTime.now().minusMinutes(45);
        var trace = trace("t-1", TraceStatus.RUNNING, started, ZonedDateTime.now().minusMinutes(1));
        stubStaleTraceScan(service, trace);
        stubTraceLookup(service, trace);
        when(service.agentRunCollection.find(any(Query.class))).thenReturn(List.of(run("run-1", RunStatus.RUNNING, started, "t-1")));

        service.cleanup();

        verify(service.traceCollection, never()).update(any(Bson.class), any(Bson.class));
        verify(service.agentRunCollection, never()).update(any(Bson.class), any(Bson.class));
    }

    @Test
    void ignoresTracesStillReceivingSpans() {
        var service = service();
        var started = ZonedDateTime.now().minusHours(2);
        var trace = trace("t-1", TraceStatus.RUNNING, started, ZonedDateTime.now());
        stubStaleTraceScan(service, trace);
        when(service.agentRunCollection.find(any(Query.class))).thenReturn(List.of());

        service.cleanup();

        verify(service.traceCollection, never()).update(any(Bson.class), any(Bson.class));
    }

    @Test
    void failsRunWithoutTrace() {
        var service = service();
        var run = run("run-1", RunStatus.RUNNING, ZonedDateTime.now().minusHours(1), null);
        when(service.traceCollection.find(any(Query.class))).thenReturn(List.of());
        when(service.agentRunCollection.find(any(Query.class))).thenReturn(List.of(run));

        service.cleanup();

        assertTrue(capturedRunUpdate(service).contains("FAILED"));
    }

    private String capturedRunUpdate(StaleRunCleanupService service) {
        var update = ArgumentCaptor.forClass(Bson.class);
        verify(service.agentRunCollection).update(any(Bson.class), update.capture());
        return update.getValue().toString();
    }

    private void stubStaleTraceScan(StaleRunCleanupService service, Trace trace) {
        when(service.traceCollection.find(any(Query.class))).thenReturn(List.of(trace));
    }

    private void stubTraceLookup(StaleRunCleanupService service, Trace trace) {
        when(service.traceCollection.find(any(Bson.class))).thenReturn(List.of(trace));
    }

    private Trace trace(String traceId, TraceStatus status, ZonedDateTime startedAt, ZonedDateTime updatedAt) {
        var trace = new Trace();
        trace.traceId = traceId;
        trace.status = status;
        trace.startedAt = startedAt;
        trace.updatedAt = updatedAt;
        return trace;
    }

    private AgentRun run(String id, RunStatus status, ZonedDateTime startedAt, String traceId) {
        var run = new AgentRun();
        run.id = id;
        run.status = status;
        run.startedAt = startedAt;
        run.traceId = traceId;
        return run;
    }

    private StaleRunCleanupService service() {
        var service = new StaleRunCleanupService();
        service.traceCollection = traceCollection();
        service.agentRunCollection = agentRunCollection();
        return service;
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<Trace> traceCollection() {
        return (MongoCollection<Trace>) mock(MongoCollection.class);
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<AgentRun> agentRunCollection() {
        return (MongoCollection<AgentRun>) mock(MongoCollection.class);
    }
}
