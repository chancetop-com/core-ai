package ai.core.server.web.sse;

import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.api.server.session.sse.SseTextChunkEvent;
import ai.core.server.messaging.OwnershipStore;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.messaging.SseDeliveryMode;
import core.framework.web.sse.Channel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionChannelServiceDirectedTest {
    @Test
    @SuppressWarnings("unchecked")
    void localOwnerSubscribesToHubAndDeliversSeqAsEventId() {
        var store = new LocalOwnershipStore();
        var ownership = new SessionOwnershipRegistry(store);
        store.hostname = ownership.getHostname();
        var hubRegistry = new SessionEventHubRegistry();
        var channelService = mock(ChannelService.class);
        when(channelService.send(any(), any(), any())).thenReturn(true);
        var service = new SessionChannelService(SseDeliveryMode.DIRECTED, hubRegistry, ownership, null, "", channelService);
        var channel = (Channel<SseBaseEvent>) mock(Channel.class);

        service.connect(channel, "s-1");
        var hub = awaitHub(hubRegistry, "s-1");
        assertNotNull(hub);

        var running = new ai.core.api.server.session.sse.SseStatusChangeEvent();
        running.status = ai.core.api.server.session.SessionStatus.RUNNING;
        hub.publish(running);
        var chunk = new SseTextChunkEvent();
        chunk.content = "hello";
        hub.publish(chunk);

        verify(channelService, timeout(1_000)).send(eq("s-1"), eq("2"), any(SseBaseEvent.class));
        service.close("s-1");
    }

    private SessionEventHub awaitHub(SessionEventHubRegistry registry, String sessionId) {
        for (int i = 0; i < 50; i++) {
            var hub = registry.get(sessionId);
            if (hub != null) return hub;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static final class LocalOwnershipStore implements OwnershipStore {
        private String hostname;

        @Override
        public boolean claim(String resourceId, String ownerPod) {
            hostname = ownerPod;
            return true;
        }

        @Override
        public boolean renew(String resourceId, String ownerPod) {
            return hostname.equals(ownerPod);
        }

        @Override
        public String owner(String resourceId) {
            return hostname;
        }

        @Override
        public void release(String resourceId, String ownerPod) {
            if (hostname.equals(ownerPod)) hostname = null;
        }
    }
}
