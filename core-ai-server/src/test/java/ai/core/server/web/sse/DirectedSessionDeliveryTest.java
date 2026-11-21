package ai.core.server.web.sse;

import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.server.messaging.OwnershipStore;
import ai.core.server.messaging.PodRegistry;
import ai.core.server.messaging.SessionOwnershipRegistry;
import core.framework.web.sse.Channel;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressFBWarnings("NAB_NEEDLESS_BOOLEAN_CONSTANT_CONVERSION")
class DirectedSessionDeliveryTest {
    @Test
    @SuppressWarnings("unchecked")
    void closesTheLocalChannelAfterRepeatedRelayFailures() {
        var ownership = new SessionOwnershipRegistry(new FixedOwnerStore("owner-pod"));
        var podRegistry = new FailingPodRegistry();
        var channelService = mock(ChannelService.class);
        when(channelService.closeIfCurrent(any(), any())).thenReturn(true);
        var delivery = new DirectedSessionDelivery(new SessionEventHubRegistry(), ownership, podRegistry,
                "token", channelService, 1);
        var channel = (Channel<SseBaseEvent>) mock(Channel.class);

        try {
            delivery.connect(channel, "s-1");

            verify(channelService, timeout(5_000)).closeIfCurrent(eq("s-1"), eq(channel));
        } finally {
            delivery.stop();
        }
    }

    private static final class FixedOwnerStore implements OwnershipStore {
        private final String owner;

        private FixedOwnerStore(String owner) {
            this.owner = owner;
        }

        @Override
        public boolean claim(String resourceId, String ownerPod) {
            return false;
        }

        @Override
        public boolean renew(String resourceId, String ownerPod) {
            return false;
        }

        @Override
        public String owner(String resourceId) {
            return owner;
        }

        @Override
        public void release(String resourceId, String ownerPod) {
            // no-op
        }
    }

    private static final class FailingPodRegistry extends PodRegistry {
        private FailingPodRegistry() {
            super("local-pod", "127.0.0.1:1");
        }

        @Override
        public String address(String podId) {
            return "127.0.0.1:1";
        }
    }
}
