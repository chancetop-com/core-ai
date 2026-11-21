package ai.core.server.messaging;

import ai.core.api.server.session.sse.SseTextChunkEvent;
import ai.core.server.web.sse.SessionEventHub;
import ai.core.server.web.sse.SessionEventHubRegistry;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressFBWarnings("NAB_NEEDLESS_BOOLEAN_CONSTANT_CONVERSION")
class EventPublisherTest {
    @Test
    void ownedSessionPublishesToTheLocalHub() {
        var hubRegistry = mock(SessionEventHubRegistry.class);
        var ownershipRegistry = mock(SessionOwnershipRegistry.class);
        var podRegistry = mock(PodRegistry.class);
        var hub = new SessionEventHub("s-1");
        when(hubRegistry.get("s-1")).thenReturn(null);
        when(hubRegistry.getOrCreate("s-1")).thenReturn(hub);
        when(ownershipRegistry.isOwnerCached("s-1")).thenReturn(true);
        var publisher = new EventPublisher(hubRegistry, ownershipRegistry, podRegistry, "token");

        publisher.publish("s-1", new SseTextChunkEvent());

        assertEquals(1, hub.lastSeq());
        verify(hubRegistry).getOrCreate("s-1");
        verify(ownershipRegistry, never()).getOwner(anyString());
    }

    @Test
    void staleHubIsDroppedWhenOwnershipMoved() {
        var hubRegistry = mock(SessionEventHubRegistry.class);
        var ownershipRegistry = mock(SessionOwnershipRegistry.class);
        var podRegistry = mock(PodRegistry.class);
        var staleHub = new SessionEventHub("s-1");
        when(hubRegistry.get("s-1")).thenReturn(staleHub);
        when(ownershipRegistry.isOwnerCached("s-1")).thenReturn(false);
        when(ownershipRegistry.getOwner("s-1")).thenReturn("pod-b");
        when(podRegistry.address("pod-b")).thenReturn(null);
        var publisher = new EventPublisher(hubRegistry, ownershipRegistry, podRegistry, "token");

        publisher.publish("s-1", new SseTextChunkEvent());

        assertEquals(0, staleHub.lastSeq());
        verify(hubRegistry).remove("s-1");
        verify(hubRegistry, never()).getOrCreate("s-1");
    }

    @Test
    void staleHubIsKeptWhenTheAuthoritativeCheckStillOwnsTheSession() {
        var hubRegistry = mock(SessionEventHubRegistry.class);
        var ownershipRegistry = mock(SessionOwnershipRegistry.class);
        var podRegistry = mock(PodRegistry.class);
        var hub = new SessionEventHub("s-1");
        when(hubRegistry.get("s-1")).thenReturn(hub);
        when(ownershipRegistry.isOwnerCached("s-1")).thenReturn(false);
        when(ownershipRegistry.isOwner("s-1")).thenReturn(true);
        var publisher = new EventPublisher(hubRegistry, ownershipRegistry, podRegistry, "token");

        publisher.publish("s-1", new SseTextChunkEvent());

        assertEquals(1, hub.lastSeq());
        verify(hubRegistry, never()).remove("s-1");
        verify(ownershipRegistry, never()).getOwner(anyString());
    }

    @Test
    void unownedSessionWithoutAHubIsForwarded() {
        var hubRegistry = mock(SessionEventHubRegistry.class);
        var ownershipRegistry = mock(SessionOwnershipRegistry.class);
        var podRegistry = mock(PodRegistry.class);
        when(hubRegistry.get("s-1")).thenReturn(null);
        when(ownershipRegistry.isOwnerCached("s-1")).thenReturn(false);
        when(ownershipRegistry.getOwner("s-1")).thenReturn("pod-b");
        when(podRegistry.address("pod-b")).thenReturn(null);
        var publisher = new EventPublisher(hubRegistry, ownershipRegistry, podRegistry, "token");

        publisher.publish("s-1", new SseTextChunkEvent());

        verify(hubRegistry, never()).getOrCreate("s-1");
        verify(hubRegistry, never()).remove("s-1");
    }
}
