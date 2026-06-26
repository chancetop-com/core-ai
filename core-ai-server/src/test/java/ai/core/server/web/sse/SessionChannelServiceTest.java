package ai.core.server.web.sse;

import ai.core.api.server.session.EventType;
import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.api.server.session.sse.SseBatchToolStartEvent;
import ai.core.api.server.session.sse.SseEnvironmentOutputChunkEvent;
import ai.core.api.server.session.sse.SseErrorEvent;
import ai.core.api.server.session.sse.SseStatusChangeEvent;
import ai.core.api.server.session.sse.SseTextChunkEvent;
import core.framework.web.sse.Channel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

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
}
