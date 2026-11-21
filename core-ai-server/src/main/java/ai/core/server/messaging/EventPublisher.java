package ai.core.server.messaging;

import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.server.web.sse.SessionEventHubRegistry;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * @author stephen
 */
public class EventPublisher {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventPublisher.class);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final SessionEventHubRegistry hubRegistry;
    private final SessionOwnershipRegistry ownershipRegistry;
    private final PodRegistry podRegistry;
    private final String token;

    public EventPublisher(SessionEventHubRegistry hubRegistry, SessionOwnershipRegistry ownershipRegistry,
                          PodRegistry podRegistry, String token) {
        this.hubRegistry = hubRegistry;
        this.ownershipRegistry = ownershipRegistry;
        this.podRegistry = podRegistry;
        this.token = token;
    }

    public void publish(String sessionId, SseBaseEvent event) {
        SseEventHelper.initEvent(event, sessionId);

        var hub = hubRegistry.get(sessionId);
        if (ownershipRegistry.isOwnerCached(sessionId)) {
            if (hub == null) hub = hubRegistry.getOrCreate(sessionId);
            hub.publish(event);
            return;
        }
        if (hub != null && ownershipRegistry.isOwner(sessionId)) {
            // the short ownership cache may have gone stale while a turn is still running on this pod;
            // do not drop a live hub just because the cached answer expired
            hub.publish(event);
            return;
        }
        if (hub != null) {
            // the session moved to another pod; a hub kept from the previous ownership epoch must not
            // swallow events that only the new owner can deliver
            LOGGER.info("dropping stale session event hub, sessionId={}", sessionId);
            hubRegistry.remove(sessionId);
        }
        forwardToOwner(sessionId, event);
    }

    private void forwardToOwner(String sessionId, SseBaseEvent event) {
        var owner = ownershipRegistry.getOwner(sessionId);
        if (owner == null || owner.isBlank()) return;
        var address = podRegistry.address(owner);
        if (address == null || address.isBlank()) return;
        try {
            var body = JsonUtil.toJson(new InternalSseEvent(sessionId, SseEventCodec.toJson(event)));
            var request = HttpRequest.newBuilder(URI.create("http://" + address + "/_internal/sessions/events"))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                LOGGER.warn("failed to forward session event to owner, sessionId={}, owner={}, status={}",
                        sessionId, owner, response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.warn("failed to forward session event to owner, sessionId={}, owner={}", sessionId, owner, e);
        }
    }
}
