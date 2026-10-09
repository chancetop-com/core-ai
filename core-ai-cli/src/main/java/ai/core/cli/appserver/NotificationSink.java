package ai.core.cli.appserver;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sink for engine-initiated JSON-RPC notifications ({@code session/event}, {@code engine/ready},
 * {@code engine/heartbeat}); implemented by the transport, safe to call from engine threads.
 *
 * @author stephen
 */
public interface NotificationSink {
    void notify(String method, JsonNode params);
}
