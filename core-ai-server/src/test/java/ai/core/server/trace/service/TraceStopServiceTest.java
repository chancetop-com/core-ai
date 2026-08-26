package ai.core.server.trace.service;

import ai.core.server.messaging.CommandPublisher;
import ai.core.server.messaging.CommandType;
import ai.core.server.messaging.SessionCommand;
import ai.core.server.messaging.TurnStateRegistry;
import ai.core.server.run.AgentRunService;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceStatus;
import com.mongodb.MongoClientSettings;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.impl.ZonedDateTimeCodec;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.bson.BsonDocument;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceStopServiceTest {
    private TraceStopService service;
    private TraceService traceService;
    private MongoCollection<Trace> traceCollection;
    private TurnStateRegistry turnStateRegistry;
    private CommandPublisher commandPublisher;
    private AgentRunService agentRunService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        traceService = mock(TraceService.class);
        traceCollection = mock(MongoCollection.class);
        turnStateRegistry = mock(TurnStateRegistry.class);
        commandPublisher = mock(CommandPublisher.class);
        agentRunService = mock(AgentRunService.class);

        service = new TraceStopService();
        service.traceService = traceService;
        service.traceCollection = traceCollection;
        service.turnStateRegistry = turnStateRegistry;
        service.commandPublisher = commandPublisher;
        service.agentRunService = agentRunService;
    }

    @Test
    void runningChatTraceSignalsCancelTurnAndMarksCancelled() {
        givenTrace("t1", TraceStatus.RUNNING, "sess-1");
        when(turnStateRegistry.liveness("sess-1")).thenReturn(TurnStateRegistry.TurnLiveness.RUNNING);

        var outcome = service.stop("t1", "admin-1");

        var captor = ArgumentCaptor.forClass(SessionCommand.class);
        verify(commandPublisher).publish(captor.capture());
        assertEquals(CommandType.CANCEL_TURN, captor.getValue().type());
        assertEquals("sess-1", captor.getValue().sessionId());
        assertEquals("admin-1", captor.getValue().userId());
        assertEquals("session", outcome.target());
        assertTrue(outcome.signalled());
        assertMarkedCancelled("t1", "admin-1");
    }

    @Test
    void unknownLivenessStillSignalsCancelTurn() {
        givenTrace("t1", TraceStatus.RUNNING, "sess-1");
        when(turnStateRegistry.liveness("sess-1")).thenReturn(TurnStateRegistry.TurnLiveness.UNKNOWN);

        var outcome = service.stop("t1", "admin-1");

        verify(commandPublisher).publish(any(SessionCommand.class));
        assertTrue(outcome.signalled());
    }

    @Test
    void staleChatTraceOnlyMarksCancelled() {
        givenTrace("t1", TraceStatus.RUNNING, "sess-1");
        when(turnStateRegistry.liveness("sess-1")).thenReturn(TurnStateRegistry.TurnLiveness.NOT_RUNNING);

        var outcome = service.stop("t1", "admin-1");

        verify(commandPublisher, never()).publish(any(SessionCommand.class));
        assertEquals("session", outcome.target());
        assertFalse(outcome.signalled());
        assertMarkedCancelled("t1", "admin-1");
    }

    @Test
    void runTraceCancelsAgentRun() {
        givenTrace("t1", TraceStatus.RUNNING, "run:run-9");

        var outcome = service.stop("t1", "admin-1");

        verify(agentRunService).cancel("run-9");
        verify(commandPublisher, never()).publish(any(SessionCommand.class));
        verify(turnStateRegistry, never()).liveness(anyString());
        assertEquals("run", outcome.target());
        assertTrue(outcome.signalled());
        assertMarkedCancelled("t1", "admin-1");
    }

    @Test
    void traceWithoutSessionOnlyMarksCancelled() {
        givenTrace("t1", TraceStatus.RUNNING, null);

        var outcome = service.stop("t1", "admin-1");

        verify(commandPublisher, never()).publish(any(SessionCommand.class));
        verify(agentRunService, never()).cancel(anyString());
        assertEquals("none", outcome.target());
        assertFalse(outcome.signalled());
        assertMarkedCancelled("t1", "admin-1");
    }

    @Test
    void rejectsTraceThatIsNotRunning() {
        givenTrace("t1", TraceStatus.COMPLETED, "sess-1");

        assertThrows(BadRequestException.class, () -> service.stop("t1", "admin-1"));

        verify(traceCollection, never()).update(any(Bson.class), any(Bson.class));
        verify(commandPublisher, never()).publish(any(SessionCommand.class));
    }

    @Test
    void rejectsUnknownTrace() {
        when(traceService.get("missing")).thenReturn(null);

        assertThrows(NotFoundException.class, () -> service.stop("missing", "admin-1"));
    }

    private void givenTrace(String traceId, TraceStatus status, String sessionId) {
        var trace = new Trace();
        trace.id = "id-" + traceId;
        trace.traceId = traceId;
        trace.status = status;
        trace.sessionId = sessionId;
        when(traceService.get(traceId)).thenReturn(trace);
    }

    private void assertMarkedCancelled(String traceId, String stoppedBy) {
        var filter = ArgumentCaptor.forClass(Bson.class);
        var update = ArgumentCaptor.forClass(Bson.class);
        verify(traceCollection).update(filter.capture(), update.capture());

        var registry = CodecRegistries.fromRegistries(
            CodecRegistries.fromCodecs(new ZonedDateTimeCodec()),
            MongoClientSettings.getDefaultCodecRegistry());
        var filterDoc = filter.getValue().toBsonDocument(BsonDocument.class, registry);
        assertEquals(traceId, filterDoc.getString("trace_id").getValue());

        var set = update.getValue().toBsonDocument(BsonDocument.class, registry).getDocument("$set");
        assertEquals("CANCELLED", set.getString("status").getValue());
        assertTrue(set.getString("error_message").getValue().contains(stoppedBy));
        assertTrue(set.containsKey("completed_at"));
        assertTrue(set.containsKey("updated_at"));
    }
}
