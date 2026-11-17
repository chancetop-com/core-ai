package ai.core.server.web.sse;

import ai.core.api.server.session.EventType;
import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.api.server.session.sse.SseStatusChangeEvent;
import ai.core.api.server.session.sse.SseTaskStatusEvent;
import ai.core.api.server.session.sse.SseTextChunkEvent;
import ai.core.api.server.session.sse.SseTurnCompleteEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionEventHubTest {
    @Test
    void replaysMergedChunkFromRunningBoundaryInsteadOfAppendingDuplicateText() throws Exception {
        var hub = new SessionEventHub("s-1");
        hub.publish(running());
        var first = new SseTextChunkEvent();
        first.content = "hel";
        hub.publish(first);
        var second = new SseTextChunkEvent();
        second.content = "lo";
        hub.publish(second);

        var received = new CopyOnWriteArrayList<SseBaseEvent>();
        var subscription = hub.subscribe(2, (seq, event) -> {
            received.add(event);
            return true;
        });
        Thread.sleep(100);
        subscription.close();

        assertEquals(2, received.size());
        assertEquals(SessionStatus.RUNNING, ((SseStatusChangeEvent) received.get(0)).status);
        assertEquals("hello", ((SseTextChunkEvent) received.get(1)).content);
    }

    @Test
    void exactBoundaryResumesWithoutReplayingTheMergedEvent() throws Exception {
        var hub = new SessionEventHub("s-1");
        hub.publish(running());
        var chunk = new SseTextChunkEvent();
        chunk.content = "a";
        hub.publish(chunk);
        var second = new SseTextChunkEvent();
        second.content = "b";
        hub.publish(second);

        var received = new CopyOnWriteArrayList<SseBaseEvent>();
        var subscription = hub.subscribe(3, (seq, event) -> {
            received.add(event);
            return true;
        });
        Thread.sleep(50);
        assertEquals(List.of(), received);

        var third = new SseTextChunkEvent();
        third.content = "c";
        hub.publish(third);
        Thread.sleep(100);
        subscription.close();

        assertEquals(1, received.size());
        assertEquals("c", ((SseTextChunkEvent) received.get(0)).content);
    }

    @Test
    void terminalEventClearsReplayAndDropsLateActivity() throws Exception {
        var hub = new SessionEventHub("s-1");
        hub.publish(running());
        var chunk = new SseTextChunkEvent();
        chunk.content = "partial";
        hub.publish(chunk);
        var complete = new SseTurnCompleteEvent();
        complete.output = "partial";
        hub.publish(complete);

        var late = new SseTextChunkEvent();
        late.content = "late";
        hub.publish(late);
        assertEquals(SessionStatus.IDLE, hub.status());

        var received = new CopyOnWriteArrayList<SseBaseEvent>();
        var subscription = hub.subscribe(0, (seq, event) -> {
            received.add(event);
            return true;
        });
        Thread.sleep(100);
        subscription.close();

        assertEquals(1, received.size());
        assertTrue(received.get(0) instanceof SseTurnCompleteEvent);
    }

    @Test
    void taskStatusIsDeliveredLiveButNotReplayed() throws Exception {
        var hub = new SessionEventHub("s-1");
        hub.publish(running());
        var task = new SseTaskStatusEvent();
        hub.publish(task);

        var live = new CopyOnWriteArrayList<SseBaseEvent>();
        var liveSubscription = hub.subscribe(0, (seq, event) -> {
            live.add(event);
            return true;
        });
        Thread.sleep(100);
        liveSubscription.close();
        assertEquals(1, live.size());
        assertTrue(live.get(0) instanceof SseStatusChangeEvent);

        var replay = new CopyOnWriteArrayList<SseBaseEvent>();
        var replaySubscription = hub.subscribe(0, (seq, event) -> {
            replay.add(event);
            return true;
        });
        Thread.sleep(100);
        replaySubscription.close();
        assertEquals(1, replay.size());
        assertTrue(replay.get(0) instanceof SseStatusChangeEvent);
    }

    @Test
    void mergeCopyLeavesTheAlreadyDeliveredEventUnchanged() throws Exception {
        var hub = new SessionEventHub("s-1");
        var first = new SseTextChunkEvent();
        first.content = "a";
        hub.publish(first);
        var second = new SseTextChunkEvent();
        second.content = "b";
        hub.publish(second);

        assertEquals("a", first.content);
        assertFalse(first == second);
    }

    @Test
    void codecRoundTripsTextChunk() {
        var event = new SseTextChunkEvent();
        event.content = "hello";
        event.type = EventType.TEXT_CHUNK;
        var json = ai.core.server.messaging.SseEventCodec.toJson(event);
        var decoded = (SseTextChunkEvent) ai.core.server.messaging.SseEventCodec.fromJson(json);
        assertEquals("hello", decoded.content);
        assertEquals(EventType.TEXT_CHUNK, decoded.type);
    }

    private SseStatusChangeEvent running() {
        var event = new SseStatusChangeEvent();
        event.status = SessionStatus.RUNNING;
        return event;
    }
}
