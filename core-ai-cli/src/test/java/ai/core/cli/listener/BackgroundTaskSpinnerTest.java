package ai.core.cli.listener;

import ai.core.agent.Agent;
import ai.core.api.server.session.AgentSession;
import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.cli.ui.TerminalUI;
import ai.core.llm.domain.Usage;
import ai.core.tool.tools.TaskTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tools running inside a background task stay invisible in the CLI, but their events used to
 * stop the spinner on tool start and restart it on tool result. While the CLI waits for the
 * async task (turn already finished, interactive prompt back up) that made the "Thinking..."
 * line blink on and off, and restarting it also fought the prompt line.
 *
 * @author stephen
 */
class BackgroundTaskSpinnerTest {
    private static final String SESSION = "test-session";
    private static final String TASK_ID = "bg-1";

    private StringWriter output;
    private CliEventListener listener;

    @BeforeEach
    void setUp() {
        output = new StringWriter();
        var ui = mock(TerminalUI.class);
        when(ui.getWriter()).thenReturn(new PrintWriter(output, true));
        when(ui.isAnsiSupported()).thenReturn(Boolean.TRUE);
        when(ui.getTerminalWidth()).thenReturn(120);
        var agent = mock(Agent.class);
        when(agent.getCurrentTokenUsage()).thenReturn(new Usage(0, 0, 0));
        listener = new CliEventListener(ui, mock(AgentSession.class), agent);
    }

    @AfterEach
    void tearDown() {
        listener.getPanel().stopSpinnerIfActive();
    }

    @Test
    void waitingForBackgroundTaskReportsStateWithoutAnimatingSpinner() {
        launchBackgroundTask();
        listener.onTurnComplete(TurnCompleteEvent.of(SESSION, "launched"));

        assertFalse(spinnerRunning(), "the spinner must not animate while the CLI waits at the prompt");
        assertTrue(output.toString().contains("1 background task running"),
                "the waiting state must be reported once, statically, actual: " + output);
    }

    @Test
    void backgroundToolEventsDoNotTouchSpinnerWhileWaiting() {
        launchBackgroundTask();
        listener.onTurnComplete(TurnCompleteEvent.of(SESSION, "launched"));
        String before = output.toString();

        listener.onToolStart(backgroundToolStart("call-read-1"));
        assertFalse(spinnerRunning(), "a background tool start must not start the waiting spinner");

        listener.onToolResult(backgroundToolResult("call-read-1"));
        assertFalse(spinnerRunning(), "a background tool result must not start the waiting spinner");

        assertEquals(before, output.toString(), "background tool events must not write to the terminal");
    }

    @Test
    void backgroundToolEventsDoNotStopSpinnerDuringTurn() {
        launchBackgroundTask();
        listener.onStatusChange(StatusChangeEvent.of(SESSION, SessionStatus.RUNNING));
        assertTrue(spinnerRunning());

        listener.onToolStart(backgroundToolStart("call-read-1"));
        assertTrue(spinnerRunning(), "a background tool start must not stop the running turn's spinner");

        listener.onToolResult(backgroundToolResult("call-read-1"));
        assertTrue(spinnerRunning(), "a background tool result must not stop the running turn's spinner");
    }

    @Test
    void mainTurnToolEventsStillDriveSpinner() {
        listener.onStatusChange(StatusChangeEvent.of(SESSION, SessionStatus.RUNNING));
        assertTrue(spinnerRunning());

        listener.onTextChunk(TextChunkEvent.of(SESSION, "hi"));
        assertFalse(spinnerRunning());

        listener.onToolResult(ToolResultEvent.of(SESSION, "call-main", "read_file", "success", "content"));
        assertTrue(spinnerRunning());
    }

    private void launchBackgroundTask() {
        var start = ToolStartEvent.of(SESSION, "call-task", TaskTool.TOOL_NAME, "{\"run_in_background\":true}");
        start.taskId = TASK_ID;
        start.runInBackground = Boolean.TRUE;
        listener.onToolStart(start);

        var result = ToolResultEvent.of(SESSION, "call-task", TaskTool.TOOL_NAME, "async_launched", "<task-notification/>");
        result.taskId = TASK_ID;
        listener.onToolResult(result);
    }

    private ToolStartEvent backgroundToolStart(String callId) {
        var start = ToolStartEvent.of(SESSION, callId, "read_file", "{\"file_path\":\"/skills/foo/SKILL.md\"}");
        start.taskId = TASK_ID;
        return start;
    }

    private ToolResultEvent backgroundToolResult(String callId) {
        var result = ToolResultEvent.of(SESSION, callId, "read_file", "success", "content");
        result.taskId = TASK_ID;
        return result;
    }

    private boolean spinnerRunning() {
        return listener.getPanel().getSpinner().isRunning();
    }
}
