package ai.core.server.web.sse;

import ai.core.api.server.session.EventType;
import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.api.server.session.sse.SseBatchToolStartEvent;
import ai.core.api.server.session.sse.SseCustomEvent;
import ai.core.api.server.session.sse.SseEnvironmentOutputChunkEvent;
import ai.core.api.server.session.sse.SseErrorEvent;
import ai.core.api.server.session.sse.SseStatusChangeEvent;
import ai.core.api.server.session.sse.SseTextChunkEvent;
import core.framework.web.sse.Channel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SessionChannelServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void terminalStatusClearsReplayAndDropsLateChunksUntilNextRun() {
        var service = new SessionChannelService();
        service.channelService = mock(ChannelService.class);
        service.connect((Channel<SseBaseEvent>) mock(Channel.class), "s-1");

        var running = new SseStatusChangeEvent();
        running.status = SessionStatus.RUNNING;
        service.send("s-1", running);
        var chunk = new SseTextChunkEvent();
        chunk.content = "partial";
        service.send("s-1", chunk);

        assertEquals(SessionStatus.RUNNING, service.status("s-1"));
        assertEquals(2, service.getEventBuffer("s-1").size());

        var idle = new SseStatusChangeEvent();
        idle.status = SessionStatus.IDLE;
        service.send("s-1", idle);

        assertEquals(SessionStatus.IDLE, service.status("s-1"));
        assertEquals(List.of(idle), service.getEventBuffer("s-1"));

        var lateChunk = new SseTextChunkEvent();
        lateChunk.content = "late";
        service.send("s-1", lateChunk);

        assertEquals(List.of(idle), service.getEventBuffer("s-1"));

        var nextRunning = new SseStatusChangeEvent();
        nextRunning.status = SessionStatus.RUNNING;
        service.send("s-1", nextRunning);

        assertEquals(SessionStatus.RUNNING, service.status("s-1"));
        assertEquals(List.of(nextRunning), service.getEventBuffer("s-1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void assignsTypesForAllStreamedSessionEvents() {
        var service = new SessionChannelService();
        service.channelService = mock(ChannelService.class);
        service.connect((Channel<SseBaseEvent>) mock(Channel.class), "s-1");

        var environmentOutput = new SseEnvironmentOutputChunkEvent();
        environmentOutput.source = "shell";
        environmentOutput.callId = "call-1";
        environmentOutput.chunk = "stdout";
        service.send("s-1", environmentOutput);

        var batch = new SseBatchToolStartEvent();
        batch.group = "parallel";
        var tool = new SseBatchToolStartEvent.ToolInfo();
        tool.callId = "call-2";
        tool.toolName = "read_file";
        tool.arguments = "{}";
        batch.tools = List.of(tool);
        service.send("s-1", batch);

        assertEquals(EventType.ENVIRONMENT_OUTPUT_CHUNK, environmentOutput.type);
        assertEquals(EventType.BATCH_TOOL_START, batch.type);
    }

    @Test
    @SuppressWarnings("unchecked")
    void errorStatusKeepsErrorMessageButDropsOldStreamChunks() {
        var service = new SessionChannelService();
        service.channelService = mock(ChannelService.class);
        service.connect((Channel<SseBaseEvent>) mock(Channel.class), "s-1");

        var running = new SseStatusChangeEvent();
        running.status = SessionStatus.RUNNING;
        service.send("s-1", running);
        var chunk = new SseTextChunkEvent();
        chunk.content = "partial";
        service.send("s-1", chunk);
        var error = new SseErrorEvent();
        error.message = "failed";
        service.send("s-1", error);
        var errorStatus = new SseStatusChangeEvent();
        errorStatus.status = SessionStatus.ERROR;
        service.send("s-1", errorStatus);

        assertEquals(SessionStatus.ERROR, service.status("s-1"));
        assertEquals(List.of(error, errorStatus), service.getEventBuffer("s-1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void customEventIsTypedAndBufferedAsTurnActivity() {
        var service = new SessionChannelService();
        service.channelService = mock(ChannelService.class);
        service.connect((Channel<SseBaseEvent>) mock(Channel.class), "s-1");

        var running = new SseStatusChangeEvent();
        running.status = SessionStatus.RUNNING;
        service.send("s-1", running);

        var custom = new SseCustomEvent();
        custom.name = "menu_table";
        custom.data = "{\"rows\":[]}";
        service.send("s-1", custom);

        assertEquals(EventType.CUSTOM, custom.type);
        assertEquals(2, service.getEventBuffer("s-1").size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void byteBudgetEvictsTheOldestReplayEventsButStillDeliversLive() {
        var service = new SessionChannelService();
        var channelService = mock(ChannelService.class);
        service.channelService = channelService;
        service.maxBufferBytes = 1200;
        service.connect((Channel<SseBaseEvent>) mock(Channel.class), "s-1");

        var first = environmentChunk("a".repeat(200));
        service.send("s-1", first);
        var second = environmentChunk("b".repeat(200));
        service.send("s-1", second);

        assertEquals(List.of(second), service.getEventBuffer("s-1"));
        assertEquals(1, service.droppedTotal());
        verify(channelService, times(2)).send(eq("s-1"), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void singleEventLargerThanTheBudgetIsSentButNotRetained() {
        var service = new SessionChannelService();
        var channelService = mock(ChannelService.class);
        service.channelService = channelService;
        service.maxBufferBytes = 500;
        service.connect((Channel<SseBaseEvent>) mock(Channel.class), "s-1");

        var huge = environmentChunk("x".repeat(1000));
        service.send("s-1", huge);

        assertEquals(List.of(), service.getEventBuffer("s-1"));
        assertEquals(1, service.droppedTotal());
        verify(channelService).send("s-1", huge);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mergedChunksCountOnceTowardTheBudget() {
        var service = new SessionChannelService();
        service.channelService = mock(ChannelService.class);
        service.connect((Channel<SseBaseEvent>) mock(Channel.class), "s-1");

        var first = textChunk("a".repeat(100));
        service.send("s-1", first);
        var second = textChunk("b".repeat(100));
        service.send("s-1", second);

        assertEquals(1, service.bufferedEvents());
        assertEquals(696, service.bufferedBytes());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aLongMergedTurnCannotOutgrowTheBudget() {
        var service = new SessionChannelService();
        var channelService = mock(ChannelService.class);
        service.channelService = channelService;
        service.maxBufferBytes = 600;
        service.connect((Channel<SseBaseEvent>) mock(Channel.class), "s-1");

        service.send("s-1", textChunk("a".repeat(100)));
        service.send("s-1", textChunk("b".repeat(100)));

        assertEquals(0, service.bufferedEvents());
        assertEquals(1, service.droppedTotal());
        verify(channelService, times(2)).send(eq("s-1"), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void startingANewTurnReleasesTheReplayBudget() {
        var service = new SessionChannelService();
        service.channelService = mock(ChannelService.class);
        service.connect((Channel<SseBaseEvent>) mock(Channel.class), "s-1");
        service.send("s-1", environmentChunk("a".repeat(200)));
        assertEquals(696, service.bufferedBytes());

        var running = new SseStatusChangeEvent();
        running.status = SessionStatus.RUNNING;
        service.send("s-1", running);

        assertEquals(1, service.bufferedEvents());
        assertEquals(256, service.bufferedBytes());
    }

    private SseTextChunkEvent textChunk(String content) {
        var event = new SseTextChunkEvent();
        event.content = content;
        return event;
    }

    private SseEnvironmentOutputChunkEvent environmentChunk(String chunk) {
        var event = new SseEnvironmentOutputChunkEvent();
        event.source = "shell";
        event.callId = "call-1";
        event.chunk = chunk;
        return event;
    }
}
