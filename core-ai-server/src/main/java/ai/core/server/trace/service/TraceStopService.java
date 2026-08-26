package ai.core.server.trace.service;

import ai.core.server.messaging.CommandPublisher;
import ai.core.server.messaging.SessionCommand;
import ai.core.server.messaging.TurnStateRegistry;
import ai.core.server.run.AgentRunService;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceStatus;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;

/**
 * Stops the live execution behind a RUNNING trace and marks the trace CANCELLED.
 * Chat/test turns are cancelled through the session command bus (cluster-wide);
 * api/scheduled/a2a runs go through AgentRunService. Traces whose execution is
 * already gone are still closed so they stop showing as RUNNING.
 *
 * @author Xander
 */
public class TraceStopService {
    static final String RUN_SESSION_PREFIX = "run:";
    static final String TARGET_SESSION = "session";
    static final String TARGET_RUN = "run";
    static final String TARGET_NONE = "none";
    private static final Logger LOGGER = LoggerFactory.getLogger(TraceStopService.class);

    @Inject
    TraceService traceService;
    @Inject
    MongoCollection<Trace> traceCollection;
    @Inject
    TurnStateRegistry turnStateRegistry;
    @Inject
    CommandPublisher commandPublisher;
    @Inject
    AgentRunService agentRunService;

    public StopOutcome stop(String traceId, String stoppedBy) {
        var trace = traceService.get(traceId);
        if (trace == null) throw new NotFoundException("trace not found");
        if (trace.status != TraceStatus.RUNNING) throw new BadRequestException("trace is not running");

        var outcome = signalCancel(trace, stoppedBy);
        markCancelled(trace.traceId, stoppedBy);
        LOGGER.info("trace stopped by admin, traceId={}, target={}, signalled={}, stoppedBy={}",
            trace.traceId, outcome.target(), outcome.signalled(), stoppedBy);
        return outcome;
    }

    private StopOutcome signalCancel(Trace trace, String stoppedBy) {
        var sessionId = trace.sessionId;
        if (sessionId == null || sessionId.isBlank()) return new StopOutcome(TARGET_NONE, false);
        if (sessionId.startsWith(RUN_SESSION_PREFIX)) {
            agentRunService.cancel(sessionId.substring(RUN_SESSION_PREFIX.length()));
            return new StopOutcome(TARGET_RUN, true);
        }
        if (turnStateRegistry.liveness(sessionId) == TurnStateRegistry.TurnLiveness.NOT_RUNNING) {
            return new StopOutcome(TARGET_SESSION, false);
        }
        commandPublisher.publish(SessionCommand.cancelTurn(sessionId, stoppedBy));
        return new StopOutcome(TARGET_SESSION, true);
    }

    private void markCancelled(String traceId, String stoppedBy) {
        var now = ZonedDateTime.now();
        traceCollection.update(Filters.eq("trace_id", traceId), Updates.combine(
            Updates.set("status", TraceStatus.CANCELLED.name()),
            Updates.set("error_message", "stopped by admin " + stoppedBy),
            Updates.set("completed_at", now),
            Updates.set("updated_at", now)));
    }

    public record StopOutcome(String target, boolean signalled) {
    }
}
