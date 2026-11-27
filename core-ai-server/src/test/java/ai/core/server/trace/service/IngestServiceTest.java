package ai.core.server.trace.service;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.trace.SpanStore;
import ai.core.server.trace.TraceStore;
import ai.core.server.trace.TraceUpdate;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceStatus;
import ai.core.server.trace.web.ingest.IngestPayloadRequest;
import ai.core.server.trace.web.ingest.IngestRequest;
import ai.core.server.trace.web.ingest.IngestSpanRequest;
import ai.core.server.trace.web.ingest.IngestStartRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IngestServiceTest {
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String DATA_URL = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9d8AAAAASUVORK5CYII=";

    @Test
    void resolveUserIdPrefersAuthenticatedOverAttribute() {
        // Authenticated userId from Bearer always wins over client-supplied attribute (route B).
        assertEquals("auth-user", IngestService.resolveUserId("auth-user", Map.of("user.id", "spoofed")));
    }

    @Test
    void resolveUserIdFallsBackToAttributeWhenUnauthenticated() {
        // Legacy anonymous path (authUserId == null) keeps the old attribute behavior.
        assertEquals("attr-user", IngestService.resolveUserId(null, Map.of("user.id", "attr-user")));
    }

    @Test
    void resolveUserIdNullWhenNeitherPresent() {
        assertNull(IngestService.resolveUserId(null, null));
    }

    @Test
    void resolveUserIdFallsBackWhenAuthUserIdIsBlank() {
        // A blank Bearer-resolved id must not be written as the user; fall back to the attribute.
        assertEquals("attr-user", IngestService.resolveUserId("  ", Map.of("user.id", "attr-user")));
    }

    @Test
    void friendlyTraceNameUsesAgentNameForOperationSpan() {
        assertEquals("Support Summarizer", IngestService.friendlyTraceName("agent.run", "Support Summarizer"));
    }

    @Test
    void friendlyTraceNameKeepsSpecificSpanName() {
        assertEquals("classify_ticket", IngestService.friendlyTraceName("classify_ticket", "Support Summarizer"));
    }

    @Test
    void startTraceCreatesRunningAgentRowWithoutSpans() {
        var harness = new Harness();
        when(harness.traceStore.insertIfAbsent(any())).thenReturn(Boolean.TRUE);
        var req = new IngestStartRequest();
        req.traceId = TRACE_ID;
        req.input = "user prompt";
        req.startedAtEpochMs = System.currentTimeMillis() - 1000L;
        req.serviceName = "core-ai-cli";
        req.serviceVersion = "2.0.0";
        req.environment = "dev";

        harness.service.startTrace(req, "user-1", "cli");

        var captor = ArgumentCaptor.forClass(Trace.class);
        verify(harness.traceStore).insertIfAbsent(captor.capture());
        var trace = captor.getValue();
        assertEquals(TRACE_ID, trace.traceId);
        assertEquals("cli turn", trace.name);
        assertEquals("agent", trace.type);
        assertEquals(TraceStatus.RUNNING, trace.status);
        assertEquals("cli", trace.source);
        assertEquals("user-1", trace.userId);
        assertEquals("user prompt", trace.input);
        assertEquals(0L, trace.durationMs);
        assertNull(trace.completedAt);
        assertNull(trace.output);
        assertNull(trace.errorMessage);
        assertEquals(0L, trace.inputTokens);
        assertEquals(0L, trace.outputTokens);
        assertEquals(0L, trace.totalTokens);
        assertEquals(0L, trace.cachedTokens);
        assertEquals(0.0, trace.costUsd);
        assertEquals("core-ai-cli", trace.metadata.get("service"));
        assertEquals("2.0.0", trace.metadata.get("version"));
        assertEquals("dev", trace.metadata.get("environment"));
        assertNotNull(trace.startedAt);
        assertNotNull(trace.createdAt);
        assertEquals(trace.createdAt, trace.updatedAt);
        verifyNoInteractions(harness.spanStore);
    }

    @Test
    void startTraceIsIdempotentWhenTraceAlreadyExistsWithName() {
        var harness = new Harness();
        var existing = existingTrace(TRACE_ID, "user-1", TraceStatus.COMPLETED, "cli turn", "initial input");
        when(harness.traceStore.insertIfAbsent(any())).thenReturn(Boolean.FALSE);
        when(harness.traceStore.findByTraceId(TRACE_ID)).thenReturn(existing);
        var req = new IngestStartRequest();
        req.traceId = TRACE_ID;
        req.name = "new name";
        req.input = "new input";

        harness.service.startTrace(req, "user-1", "cli");

        verify(harness.traceStore, never()).update(any());
        verifyNoInteractions(harness.spanStore);
    }

    @Test
    void startTraceBackfillsNameOnlyWhenExistingTraceHasBlankName() {
        var harness = new Harness();
        var existing = existingTrace(TRACE_ID, "user-1", TraceStatus.RUNNING, null, null);
        when(harness.traceStore.insertIfAbsent(any())).thenReturn(Boolean.FALSE);
        when(harness.traceStore.findByTraceId(TRACE_ID)).thenReturn(existing);
        var req = new IngestStartRequest();
        req.traceId = TRACE_ID;
        req.name = "cli turn";
        req.input = "prompt";

        harness.service.startTrace(req, "user-1", "cli");

        var captor = ArgumentCaptor.forClass(TraceUpdate.class);
        verify(harness.traceStore).update(captor.capture());
        var update = captor.getValue();
        assertEquals(TRACE_ID, update.traceId);
        assertEquals(TraceUpdate.EmptyGuard.NAME, update.setOnlyWhenEmpty);
        assertEquals("cli turn", update.valueWhenEmpty);
        assertNotNull(update.updatedAt);
        assertNull(update.status);
        assertNull(update.input);
        assertNull(update.output);
        assertNull(update.completedAt);
    }

    @Test
    void startTraceDropsConflictWhenOwnerDiffers() {
        var harness = new Harness();
        var existing = existingTrace(TRACE_ID, "user-a", TraceStatus.RUNNING, null, null);
        when(harness.traceStore.insertIfAbsent(any())).thenReturn(Boolean.FALSE);
        when(harness.traceStore.findByTraceId(TRACE_ID)).thenReturn(existing);
        var req = new IngestStartRequest();
        req.traceId = TRACE_ID;
        req.name = "hijack";

        harness.service.startTrace(req, "user-b", "cli");

        verify(harness.traceStore, never()).update(any());
    }

    @Test
    void startTraceUsesServerNowWhenStartedAtIsNullOrOutOfRange() {
        var harness = new Harness();
        when(harness.traceStore.insertIfAbsent(any())).thenReturn(Boolean.TRUE);
        var nullTimeReq = new IngestStartRequest();
        nullTimeReq.traceId = TRACE_ID;
        nullTimeReq.startedAtEpochMs = null;

        var outOfRangeReq = new IngestStartRequest();
        outOfRangeReq.traceId = "fedcba9876543210fedcba9876543210";
        outOfRangeReq.startedAtEpochMs = System.currentTimeMillis() - Duration.ofDays(45).toMillis();

        harness.service.startTrace(nullTimeReq, "user-1", "cli");
        harness.service.startTrace(outOfRangeReq, "user-1", "cli");

        var captor = ArgumentCaptor.forClass(Trace.class);
        verify(harness.traceStore, times(2)).insertIfAbsent(captor.capture());
        for (var created : captor.getAllValues()) {
            assertEquals(created.createdAt, created.startedAt);
        }
    }

    @Test
    void authenticatedChildSpanBatchDoesNotModifyTraceFields() {
        var harness = new Harness();
        var existing = existingTrace(TRACE_ID, "user-1", TraceStatus.RUNNING, "cli turn", "user prompt");
        when(harness.traceStore.findByTraceId(TRACE_ID)).thenReturn(existing);
        when(harness.spanStore.insertIfAbsent(any())).thenReturn(Boolean.TRUE);

        var child = childSpan(TRACE_ID, "span-llm-1", "root-1", "LLM", "OK");
        child.output = "tool or llm output";
        child.durationMs = 450L;
        child.inputTokens = 100L;
        child.outputTokens = 50L;

        harness.service.ingest(ingestRequest(List.of(child)), "user-1", "cli");

        var updates = captureUpdates(harness.traceStore);
        assertFalse(updates.isEmpty());
        for (var update : updates) {
            assertNull(update.status);
            assertNull(update.input);
            assertNull(update.output);
            assertNull(update.durationMs);
            assertNull(update.completedAt);
        }
        verify(harness.spanStore).insertIfAbsent(any());
    }

    @Test
    void authenticatedChildSpanBatchAfterCompletionKeepsCompletedStatus() {
        var harness = new Harness();
        var existing = existingTrace(TRACE_ID, "user-1", TraceStatus.COMPLETED, "cli turn", "user prompt");
        when(harness.traceStore.findByTraceId(TRACE_ID)).thenReturn(existing);
        when(harness.spanStore.insertIfAbsent(any())).thenReturn(Boolean.TRUE);

        var child = childSpan(TRACE_ID, "span-late-1", "root-1", "TOOL", "ERROR");
        child.output = "late child error";

        harness.service.ingest(ingestRequest(List.of(child)), "user-1", "cli");

        for (var update : captureUpdates(harness.traceStore)) {
            assertNull(update.status);
            assertNull(update.errorMessage);
            assertNull(update.output);
        }
    }

    @Test
    void authenticatedChildSpanCreatesRunningAgentTraceWhenStartMissed() {
        var harness = new Harness();
        when(harness.traceStore.findByTraceId(TRACE_ID)).thenReturn(null);
        when(harness.traceStore.insertIfAbsent(any())).thenReturn(Boolean.TRUE);
        when(harness.spanStore.insertIfAbsent(any())).thenReturn(Boolean.TRUE);

        var child = childSpan(TRACE_ID, "span-llm-1", "root-1", "LLM", "OK");
        child.name = "gpt-4o";
        child.input = "child input";
        child.output = "child output";
        child.durationMs = 300L;

        harness.service.ingest(ingestRequest(List.of(child)), "user-1", "cli");

        var captor = ArgumentCaptor.forClass(Trace.class);
        verify(harness.traceStore).insertIfAbsent(captor.capture());
        var created = captor.getValue();
        assertEquals("agent", created.type);
        assertEquals(TraceStatus.RUNNING, created.status);
        assertNull(created.name);
        assertNull(created.input);
        assertNull(created.output);
        assertEquals(0L, created.durationMs);
        assertNull(created.completedAt);
        assertEquals("cli", created.source);
        assertEquals("user-1", created.userId);
    }

    @Test
    void authenticatedChildSpanRaceOnInsertIfAbsentDoesNotOverwriteRunningTrace() {
        var harness = new Harness();
        when(harness.traceStore.findByTraceId(TRACE_ID)).thenReturn(null);
        when(harness.traceStore.insertIfAbsent(any())).thenReturn(Boolean.FALSE);
        when(harness.spanStore.insertIfAbsent(any())).thenReturn(Boolean.TRUE);

        var child = childSpan(TRACE_ID, "span-llm-1", "root-1", "LLM", "OK");
        child.output = "child result";

        harness.service.ingest(ingestRequest(List.of(child)), "user-1", "cli");

        for (var update : captureUpdates(harness.traceStore)) {
            assertNull(update.status);
            assertNull(update.input);
            assertNull(update.output);
            assertNull(update.durationMs);
            assertNull(update.completedAt);
        }
    }

    @Test
    void rootSpanRaceOnInsertIfAbsentUpdatesOwnTraceAndRejectsForeignOwner() {
        var ownHarness = new Harness();
        var existingOwn = existingTrace(TRACE_ID, "user-1", TraceStatus.RUNNING, null, "prompt");
        when(ownHarness.traceStore.findByTraceId(TRACE_ID)).thenReturn(null, existingOwn);
        when(ownHarness.traceStore.insertIfAbsent(any())).thenReturn(Boolean.FALSE);
        when(ownHarness.spanStore.insertIfAbsent(any())).thenReturn(Boolean.TRUE);

        var root = rootSpan(TRACE_ID, "root-1", "cli turn", "OK");
        root.input = "prompt";
        root.output = "done";

        ownHarness.service.ingest(ingestRequest(List.of(root)), "user-1", "cli");

        var ownTerminal = captureUpdates(ownHarness.traceStore).stream()
            .filter(u -> u.status != null).findFirst().orElseThrow();
        assertEquals(TraceStatus.COMPLETED, ownTerminal.status);
        assertTrue(ownTerminal.clearErrorMessage);
        assertNull(ownTerminal.input);
        assertEquals("done", ownTerminal.output);

        var foreignHarness = new Harness();
        var existingForeign = existingTrace(TRACE_ID, "user-a", TraceStatus.RUNNING, "cli turn", "secret");
        when(foreignHarness.traceStore.findByTraceId(TRACE_ID)).thenReturn(null, existingForeign);
        when(foreignHarness.traceStore.insertIfAbsent(any())).thenReturn(Boolean.FALSE);
        when(foreignHarness.spanStore.insertIfAbsent(any())).thenReturn(Boolean.TRUE);

        foreignHarness.service.ingest(ingestRequest(List.of(root)), "user-b", "cli");

        for (var update : captureUpdates(foreignHarness.traceStore)) {
            assertNull(update.status);
            assertNull(update.output);
        }
    }

    @Test
    void anonymousChildSpanRaceOnInsertIfAbsentStillUpdatesTrace() {
        var harness = new Harness();
        var existing = existingTrace(TRACE_ID, "legacy-user", TraceStatus.RUNNING, "legacy", "old");
        when(harness.traceStore.findByTraceId(TRACE_ID)).thenReturn(null, existing);
        when(harness.traceStore.insertIfAbsent(any())).thenReturn(Boolean.FALSE);
        when(harness.spanStore.insertIfAbsent(any())).thenReturn(Boolean.TRUE);

        var child = childSpan(TRACE_ID, "span-llm-1", "parent-1", "LLM", "OK");
        child.output = "race answer";

        harness.service.ingest(ingestRequest(List.of(child)), null, null);

        var terminal = captureUpdates(harness.traceStore).stream()
            .filter(u -> u.status != null).findFirst().orElseThrow();
        assertEquals(TraceStatus.COMPLETED, terminal.status);
        assertEquals("race answer", terminal.output);
        assertFalse(terminal.clearErrorMessage);
    }

    @Test
    void anonymousChildSpanBatchPreservesLegacyBehavior() {
        var harness = new Harness();
        when(harness.traceStore.findByTraceId(TRACE_ID)).thenReturn(null);
        when(harness.traceStore.insertIfAbsent(any())).thenReturn(Boolean.TRUE);
        when(harness.spanStore.insertIfAbsent(any())).thenReturn(Boolean.TRUE);

        var child = childSpan(TRACE_ID, "span-llm-1", "parent-1", "LLM", "OK");
        child.name = "legacy-llm";
        child.input = "prompt";
        child.output = "answer";
        child.durationMs = 250L;

        harness.service.ingest(ingestRequest(List.of(child)), null, null);

        var captor = ArgumentCaptor.forClass(Trace.class);
        verify(harness.traceStore).insertIfAbsent(captor.capture());
        var created = captor.getValue();
        assertEquals("llm_call", created.type);
        assertEquals(TraceStatus.COMPLETED, created.status);
        assertEquals("legacy-llm", created.name);
        assertEquals("prompt", created.input);
        assertEquals("answer", created.output);
        assertEquals(250L, created.durationMs);
        assertNotNull(created.completedAt);
    }

    @Test
    void anonymousChildSpanUpdatesExistingTraceAsBefore() {
        var harness = new Harness();
        var existing = existingTrace(TRACE_ID, "legacy-user", TraceStatus.RUNNING, "legacy", "old");
        when(harness.traceStore.findByTraceId(TRACE_ID)).thenReturn(existing);
        when(harness.spanStore.insertIfAbsent(any())).thenReturn(Boolean.TRUE);

        var child = childSpan(TRACE_ID, "span-llm-2", "parent-1", "LLM", "OK");
        child.output = "updated answer";

        harness.service.ingest(ingestRequest(List.of(child)), null, null);

        var statusUpdates = captureUpdates(harness.traceStore).stream().filter(u -> u.status != null).toList();
        assertEquals(1, statusUpdates.size());
        assertEquals(TraceStatus.COMPLETED, statusUpdates.getFirst().status);
        assertEquals("updated answer", statusUpdates.getFirst().output);
        assertFalse(statusUpdates.getFirst().clearErrorMessage);
    }

    @Test
    void finalRootBatchWritesTerminalStateClearsErrorBackfillsNameAndSkipsDuplicateInputOffload() {
        var harness = new Harness();
        var objects = harness.enableObjectBodyOffload();
        var largeInput = "q".repeat(32 * 1024);
        var startRef = harness.bodyStore.offload(TRACE_ID, null, "in", largeInput);
        assertEquals(1, objects.size());

        var existing = existingTrace(TRACE_ID, "user-1", TraceStatus.ERROR, null, startRef);
        existing.errorMessage = "run interrupted: no activity for 30m";
        when(harness.traceStore.findByTraceId(TRACE_ID)).thenReturn(existing);
        when(harness.spanStore.insertIfAbsent(any())).thenReturn(Boolean.TRUE);

        var root = rootSpan(TRACE_ID, "root-1", "cli turn", "OK");
        root.input = largeInput;
        root.output = "final answer";
        root.durationMs = 1800L;
        root.attributes = Map.of();

        harness.service.ingest(ingestRequest(List.of(root)), "user-1", "cli");

        long traceBodyObjects = objects.keySet().stream()
            .filter(key -> key.substring(key.lastIndexOf('/') + 1).startsWith("trace-"))
            .count();
        assertEquals(1, traceBodyObjects);
        var updates = captureUpdates(harness.traceStore);
        var terminalUpdate = updates.stream().filter(u -> u.status != null).findFirst().orElseThrow();
        assertEquals(TraceStatus.COMPLETED, terminalUpdate.status);
        assertTrue(terminalUpdate.clearErrorMessage);
        assertNull(terminalUpdate.errorMessage);
        assertNull(terminalUpdate.input);
        assertEquals("final answer", terminalUpdate.output);
        assertEquals(1800L, terminalUpdate.durationMs);
        assertNotNull(terminalUpdate.completedAt);

        var nameBackfill = updates.stream()
            .filter(u -> u.setOnlyWhenEmpty == TraceUpdate.EmptyGuard.NAME)
            .findFirst().orElseThrow();
        assertEquals("cli turn", nameBackfill.valueWhenEmpty);
    }

    @Test
    void finalRootBatchWithErrorSetsErrorMessageWithoutClearingIt() {
        var harness = new Harness();
        var existing = existingTrace(TRACE_ID, "user-1", TraceStatus.RUNNING, "cli turn", "prompt");
        when(harness.traceStore.findByTraceId(TRACE_ID)).thenReturn(existing);
        when(harness.spanStore.insertIfAbsent(any())).thenReturn(Boolean.TRUE);

        var root = rootSpan(TRACE_ID, "root-1", "cli turn", "ERROR");
        root.input = "prompt";
        root.output = "tool execution failed";

        harness.service.ingest(ingestRequest(List.of(root)), "user-1", "cli");

        var terminalUpdate = captureUpdates(harness.traceStore).stream()
            .filter(u -> u.status != null).findFirst().orElseThrow();
        assertEquals(TraceStatus.ERROR, terminalUpdate.status);
        assertEquals("tool execution failed", terminalUpdate.errorMessage);
        assertFalse(terminalUpdate.clearErrorMessage);
    }

    @Test
    void crossUserAuthenticatedIngestDropsSpansAndPayloads() {
        var harness = new Harness();
        var existing = existingTrace(TRACE_ID, "user-a", TraceStatus.RUNNING, "cli turn", "secret");
        when(harness.traceStore.findByTraceId(TRACE_ID)).thenReturn(existing);

        var request = ingestRequest(List.of(rootSpan(TRACE_ID, "root-1", "cli turn", "OK")));
        var payload = new IngestPayloadRequest();
        payload.traceId = TRACE_ID;
        payload.dataUrl = DATA_URL;
        request.payloads = List.of(payload);

        harness.service.ingest(request, "user-b", "cli");

        verify(harness.traceStore, never()).insertIfAbsent(any());
        verify(harness.traceStore, never()).update(any());
        verifyNoInteractions(harness.spanStore);
        verify(harness.payloadRepository, never()).store(any());
    }

    @Test
    void mixedBatchDropsForeignTraceAndIngestsOwnTrace() {
        var harness = new Harness();
        var ownTraceId = "fedcba9876543210fedcba9876543210";
        when(harness.traceStore.findByTraceId(TRACE_ID))
            .thenReturn(existingTrace(TRACE_ID, "user-a", TraceStatus.RUNNING, "cli turn", "secret"));
        when(harness.traceStore.findByTraceId(ownTraceId)).thenReturn(null);
        when(harness.traceStore.insertIfAbsent(any())).thenReturn(Boolean.TRUE);
        when(harness.spanStore.insertIfAbsent(any())).thenReturn(Boolean.TRUE);

        var foreignSpan = rootSpan(TRACE_ID, "span-foreign", "cli turn", "OK");
        var ownSpan = rootSpan(ownTraceId, "span-own", "cli turn", "OK");
        var request = ingestRequest(List.of(foreignSpan, ownSpan));
        var foreignPayload = new IngestPayloadRequest();
        foreignPayload.traceId = TRACE_ID;
        foreignPayload.dataUrl = DATA_URL;
        var ownPayload = new IngestPayloadRequest();
        ownPayload.traceId = ownTraceId;
        ownPayload.dataUrl = DATA_URL;
        request.payloads = List.of(foreignPayload, ownPayload);

        harness.service.ingest(request, "user-b", "cli");

        var spanCaptor = ArgumentCaptor.forClass(Span.class);
        verify(harness.spanStore, times(1)).insertIfAbsent(spanCaptor.capture());
        assertEquals(ownTraceId, spanCaptor.getValue().traceId);
        verify(harness.payloadRepository, times(1)).store(any());
    }

    @Test
    void spanPreCheckSkipsCompactorOffloadAndInsertForAlreadyPersistedSpans() {
        var harness = new Harness();
        var objects = harness.enableObjectBodyOffload();
        var existing = existingTrace(TRACE_ID, "user-1", TraceStatus.RUNNING, "cli turn", "prompt");
        when(harness.traceStore.findByTraceId(TRACE_ID)).thenReturn(existing);
        when(harness.spanStore.existingSpanIds(anyList())).thenReturn(Set.of("child-existing"));
        when(harness.spanStore.insertIfAbsent(any())).thenReturn(Boolean.TRUE);

        var existingChild = childSpan(TRACE_ID, "child-existing", "root-1", "LLM", "OK");
        existingChild.input = DATA_URL + " " + "x".repeat(32 * 1024);
        existingChild.output = "y".repeat(32 * 1024);
        var newChild = childSpan(TRACE_ID, "child-new", "root-1", "TOOL", "OK");
        newChild.input = "small input";
        newChild.output = "small output";
        var root = rootSpan(TRACE_ID, "root-1", "cli turn", "OK");
        root.input = "prompt";
        root.output = "done";

        harness.service.ingest(ingestRequest(List.of(root, existingChild, newChild)), "user-1", "cli");

        // Existing child was not compacted (image not stored) and not offloaded to object storage.
        verify(harness.payloadRepository, never()).store(any());
        assertTrue(objects.isEmpty());
        assertTrue(existingChild.input.startsWith("data:image/"));

        var savedSpans = ArgumentCaptor.forClass(Span.class);
        verify(harness.spanStore, times(2)).insertIfAbsent(savedSpans.capture());
        var savedIds = savedSpans.getAllValues().stream().map(s -> s.spanId).toList();
        assertEquals(List.of("root-1", "child-new"), savedIds);
        verify(harness.spanStore).tokenTotals(List.of(TRACE_ID));
    }

    @Test
    void replayedFullBatchStillReconcilesTotalsWithoutDoubleCounting() {
        var harness = new Harness();
        var existing = existingTrace(TRACE_ID, "user-1", TraceStatus.COMPLETED, "cli turn", "prompt");
        when(harness.traceStore.findByTraceId(TRACE_ID)).thenReturn(existing);
        when(harness.spanStore.existingSpanIds(anyList())).thenReturn(Set.of("root-1", "child-1"));
        when(harness.spanStore.tokenTotals(List.of(TRACE_ID)))
            .thenReturn(List.of(new SpanStore.TokenTotals(TRACE_ID, 100L, 50L, 10L, 0.02)));

        var root = rootSpan(TRACE_ID, "root-1", "cli turn", "OK");
        var child = childSpan(TRACE_ID, "child-1", "root-1", "LLM", "OK");
        child.inputTokens = 100L;
        child.outputTokens = 50L;

        harness.service.ingest(ingestRequest(List.of(root, child)), "user-1", "cli");

        verify(harness.spanStore, never()).insertIfAbsent(any());
        verify(harness.spanStore).tokenTotals(List.of(TRACE_ID));
        var updates = captureUpdates(harness.traceStore);
        assertEquals(1, updates.size());
        assertEquals(100L, updates.getFirst().inputTokens);
        assertEquals(50L, updates.getFirst().outputTokens);
        assertNull(updates.getFirst().inputTokensDelta);
    }

    @Test
    void legacyClientFullBatchBehavesUnchanged() {
        var harness = new Harness();
        when(harness.traceStore.findByTraceId(TRACE_ID)).thenReturn(null);
        when(harness.traceStore.insertIfAbsent(any())).thenReturn(Boolean.TRUE);
        when(harness.spanStore.insertIfAbsent(any())).thenReturn(Boolean.TRUE);

        var root = rootSpan(TRACE_ID, "root-1", "cli turn", "OK");
        root.input = "question";
        root.output = "answer";
        root.durationMs = 900L;
        var child = childSpan(TRACE_ID, "child-1", "root-1", "LLM", "OK");
        child.inputTokens = 20L;
        child.outputTokens = 10L;

        harness.service.ingest(ingestRequest(List.of(child, root)), "user-1", "cli");

        var traceCaptor = ArgumentCaptor.forClass(Trace.class);
        verify(harness.traceStore).insertIfAbsent(traceCaptor.capture());
        var created = traceCaptor.getValue();
        assertEquals("agent", created.type);
        assertEquals(TraceStatus.COMPLETED, created.status);
        assertEquals("cli turn", created.name);
        assertEquals("question", created.input);
        assertEquals("answer", created.output);
        assertEquals(900L, created.durationMs);
        assertEquals("user-1", created.userId);
        assertEquals("cli", created.source);
        verify(harness.spanStore, times(2)).insertIfAbsent(any());
        verify(harness.spanStore).tokenTotals(List.of(TRACE_ID));
    }

    private List<TraceUpdate> captureUpdates(TraceStore traceStore) {
        var captor = ArgumentCaptor.forClass(TraceUpdate.class);
        verify(traceStore, atLeast(0)).update(captor.capture());
        return captor.getAllValues();
    }

    private Trace existingTrace(String traceId, String userId, TraceStatus status, String name, String input) {
        var trace = new Trace();
        trace.id = "id-" + traceId;
        trace.traceId = traceId;
        trace.userId = userId;
        trace.status = status;
        trace.name = name;
        trace.input = input;
        trace.type = "agent";
        trace.source = "cli";
        trace.createdAt = ZonedDateTime.now().minusMinutes(1);
        trace.updatedAt = trace.createdAt;
        return trace;
    }

    private IngestRequest ingestRequest(List<IngestSpanRequest> spans) {
        var request = new IngestRequest();
        request.serviceName = "core-ai-cli";
        request.serviceVersion = "2.0.0";
        request.environment = "dev";
        request.spans = new ArrayList<>(spans);
        return request;
    }

    private IngestSpanRequest rootSpan(String traceId, String spanId, String name, String status) {
        var span = new IngestSpanRequest();
        span.traceId = traceId;
        span.spanId = spanId;
        span.parentSpanId = null;
        span.name = name;
        span.type = "AGENT";
        span.status = status;
        span.costUsd = 0.0;
        span.durationMs = 100L;
        span.startedAtEpochMs = System.currentTimeMillis() - 100L;
        span.completedAtEpochMs = System.currentTimeMillis();
        return span;
    }

    private IngestSpanRequest childSpan(String traceId, String spanId, String parentSpanId, String type, String status) {
        var span = new IngestSpanRequest();
        span.traceId = traceId;
        span.spanId = spanId;
        span.parentSpanId = parentSpanId;
        span.name = "step";
        span.type = type;
        span.status = status;
        span.costUsd = 0.0;
        span.durationMs = 50L;
        span.startedAtEpochMs = System.currentTimeMillis() - 50L;
        span.completedAtEpochMs = System.currentTimeMillis();
        return span;
    }

    private static final class Harness {
        final TraceStore traceStore = mock(TraceStore.class);
        final SpanStore spanStore = mock(SpanStore.class);
        final ModelPricingService modelPricingService = mock(ModelPricingService.class);
        final TracePayloadRepository payloadRepository = mock(TracePayloadRepository.class);
        final TraceBodyStore bodyStore = new TraceBodyStore();
        final TracePayloadStore payloadStore = new TracePayloadStore();
        final IngestService service = new IngestService();

        Harness() {
            when(payloadRepository.store(any())).thenReturn(Boolean.TRUE);
            when(spanStore.existingSpanIds(anyList())).thenReturn(Set.of());
            when(spanStore.tokenTotals(anyList())).thenReturn(List.of());
            bodyStore.configure("false", 16 * 1024, null, null, false);
            payloadStore.payloadRepository = payloadRepository;
            payloadStore.bodyStore = bodyStore;
            service.traceStore = traceStore;
            service.spanStore = spanStore;
            service.modelPricingService = modelPricingService;
            service.payloadStore = payloadStore;
            service.bodyStore = bodyStore;
        }

        Map<String, byte[]> enableObjectBodyOffload() {
            var objects = new HashMap<String, byte[]>();
            var storage = mock(ObjectStorageService.class);
            doAnswer(invocation -> {
                objects.put(invocation.getArgument(1), invocation.getArgument(2));
                return null;
            }).when(storage).uploadObject(anyString(), anyString(), any(byte[].class), anyString());
            var resolver = mock(ObjectStorageServiceResolver.class);
            when(resolver.resolve()).thenReturn(storage);
            when(resolver.artifactContainer()).thenReturn("artifacts");
            bodyStore.objectStorageResolver = resolver;
            bodyStore.configure("true", 16 * 1024, "trace-container", "dev", true);
            return objects;
        }
    }
}
