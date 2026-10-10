package ai.core.cli.memory;

import ai.core.agent.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fan-out for memory extraction reports. A client registers a listener once and receives started /
 * completed reports, each tagged with the agent the run was forked from so the caller can map the
 * run to its session.
 *
 * @author stephen
 */
final class MemoryActivityPublisher {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryActivityPublisher.class);

    private final List<MemoryActivityListener> listeners = new CopyOnWriteArrayList<>();

    void addListener(MemoryActivityListener listener) {
        listeners.add(listener);
    }

    void publish(Agent agent, MemoryExtractionReport report) {
        if (agent == null) {
            return;
        }
        for (MemoryActivityListener listener : listeners) {
            try {
                listener.onMemoryActivity(agent, report);
            } catch (RuntimeException e) {
                LOGGER.warn("memory activity listener failed: {}", e.getMessage());
            }
        }
    }
}
