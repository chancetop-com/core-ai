package ai.core.cli.appserver;

import ai.core.agent.Agent;
import ai.core.api.server.session.CustomEvent;
import ai.core.cli.memory.MemoryExtractionReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * Routes a memory extraction report to the session whose agent the run was forked from, as a
 * {@code memory} custom event. Reports for agents no live session owns are dropped.
 */
final class MemoryActivityReporter {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryActivityReporter.class);

    private final Supplier<Collection<EngineSession>> sessions;

    MemoryActivityReporter(Supplier<Collection<EngineSession>> sessions) {
        this.sessions = sessions;
    }

    void publish(Agent agent, MemoryExtractionReport report) {
        sessions.get().stream()
                .filter(session -> session.agent().equals(agent))
                .findFirst()
                .ifPresent(session -> dispatch(session, report));
    }

    private void dispatch(EngineSession session, MemoryExtractionReport report) {
        try {
            var data = MemoryActivityJson.payload(report).toString();
            session.inProcess().dispatchEvent(CustomEvent.of(session.id(), "memory", data, null, null));
        } catch (RuntimeException e) {
            LOGGER.warn("failed to publish memory activity, sessionId={}, error={}", session.id(), e.getMessage());
        }
    }
}
