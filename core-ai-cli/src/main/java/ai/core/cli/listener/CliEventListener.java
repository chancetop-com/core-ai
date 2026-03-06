package ai.core.cli.listener;

import ai.core.agent.Agent;
import ai.core.api.server.session.AgentSession;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.cli.DebugLog;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.cli.ui.ThinkingSpinner;

import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author stephen
 */
public class CliEventListener extends BaseEventListener {

    private static final int ESC = 27;

    private static String truncate(String text) {
        if (text == null) return "null";
        return text.length() <= 200 ? text : text.substring(0, 200) + "...(" + text.length() + " chars)";
    }

    private final Agent agent;
    private final AtomicBoolean turnRunning = new AtomicBoolean(false);
    private volatile long turnTokensBefore;
    private volatile long turnPromptTokensBefore;
    private volatile long turnCompletionTokensBefore;
    private Thread escReaderThread;

    public CliEventListener(TerminalUI ui, AgentSession session, Agent agent) {
        super(ui, session);
        this.agent = agent;
    }

    @Override
    public void prepareTurn() {
        super.prepareTurn();
        turnRunning.set(true);
        var usage = agent.getCurrentTokenUsage();
        turnTokensBefore = usage.getTotalTokens();
        turnPromptTokensBefore = usage.getPromptTokens();
        turnCompletionTokensBefore = usage.getCompletionTokens();
        spinner.setStatsSupplier(() -> {
            var u = agent.getCurrentTokenUsage();
            long tokens = u.getTotalTokens() - turnTokensBefore;
            if (tokens == 0) return null;
            long input = u.getPromptTokens() - turnPromptTokensBefore;
            long output = u.getCompletionTokens() - turnCompletionTokensBefore;
            return String.format("%,d tokens (\u2191 %,d \u2193 %,d)", tokens, input, output);
        });
        startEscReader();
    }

    @Override
    public void waitForTurn() {
        super.waitForTurn();
        turnRunning.set(false);
        stopEscReader();
    }

    @Override
    public void onTextChunk(TextChunkEvent event) {
        DebugLog.log("text chunk: length=" + event.chunk.length());
        super.onTextChunk(event);
    }

    @Override
    public void onToolStart(ToolStartEvent event) {
        DebugLog.log("tool start: " + event.toolName + " callId=" + event.callId + " args=" + truncate(event.arguments));
        super.onToolStart(event);
        stopSpinnerIfActive();
        markdownRenderer.flush();
        showSkillHintIfApplicable(event);
        ui.showToolStart(event.toolName, event.arguments);
    }

    private void showSkillHintIfApplicable(ToolStartEvent event) {
        if (!"read_file".equals(event.toolName) || event.arguments == null) return;
        String skillName = extractSkillName(event.arguments);
        if (skillName != null) {
            ui.getWriter().println("\n  " + AnsiTheme.CMD_NAME + "\uD83D\uDCD6 Using skill: " + skillName + AnsiTheme.RESET);
            ui.getWriter().flush();
        }
    }

    private String extractSkillName(String arguments) {
        int idx = arguments.indexOf("SKILL.md");
        if (idx < 0) return null;
        String before = arguments.substring(0, idx);
        int lastSlash = before.lastIndexOf('/');
        if (lastSlash < 0) return null;
        int secondLastSlash = before.lastIndexOf('/', lastSlash - 1);
        if (secondLastSlash < 0) return null;
        return before.substring(secondLastSlash + 1, lastSlash);
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        DebugLog.log("tool result: " + event.toolName + " callId=" + event.callId + " status=" + event.status + " result=" + truncate(event.result));
        super.onToolResult(event);
    }

    @Override
    public void onToolApprovalRequest(ToolApprovalRequestEvent event) {
        DebugLog.log("tool approval request: " + event.toolName + " callId=" + event.callId);
        super.onToolApprovalRequest(event);
        DebugLog.log("tool approval sent: " + event.toolName + " callId=" + event.callId);
    }

    @Override
    protected void printTurnSummary() {
        long elapsed = spinner.getElapsedMs();
        var usage = agent.getCurrentTokenUsage();
        long turnTokens = usage.getTotalTokens() - turnTokensBefore;
        long turnInput = usage.getPromptTokens() - turnPromptTokensBefore;
        long turnOutput = usage.getCompletionTokens() - turnCompletionTokensBefore;
        String time = ThinkingSpinner.formatElapsed(elapsed);
        String tokenDetail = String.format("%,d tokens (\u2191 %,d \u2193 %,d)", turnTokens, turnInput, turnOutput);
        ui.getWriter().println("\n" + AnsiTheme.MUTED + "  \u2726 " + time
                + " | " + tokenDetail + AnsiTheme.RESET);
        ui.getWriter().flush();
    }

    private void startEscReader() {
        var ttyFile = new File("/dev/tty");
        if (!ttyFile.exists()) return;
        escReaderThread = new Thread(() -> {
            stty("-icanon", "-echo");
            boolean escPressed = pollEscKey(ttyFile);
            stty("sane");
            if (escPressed) {
                DebugLog.log("ESC pressed, cancelling turn");
                session.cancelTurn();
            }
        }, "esc-reader");
        escReaderThread.setDaemon(true);
        escReaderThread.start();
    }

    @SuppressWarnings("PMD.AvoidFileStream")
    private boolean pollEscKey(File ttyFile) {
        try (var ttyIn = new FileInputStream(ttyFile)) {
            byte[] buf = new byte[8];
            while (turnRunning.get() && !Thread.currentThread().isInterrupted()) {
                if (ttyIn.available() > 0) {
                    int n = ttyIn.read(buf);
                    if (n > 0 && buf[0] == ESC && n == 1) {
                        return true;
                    }
                } else {
                    Thread.sleep(50);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            DebugLog.log("esc reader error: " + e.getMessage());
        }
        return false;
    }

    private void stopEscReader() {
        if (escReaderThread != null) {
            escReaderThread.interrupt();
            escReaderThread = null;
        }
        stty("sane");
    }

    private void stty(String... args) {
        try {
            var cmd = new String[args.length + 1];
            cmd[0] = "stty";
            System.arraycopy(args, 0, cmd, 1, args.length);
            new ProcessBuilder(cmd)
                    .redirectInput(new File("/dev/tty"))
                    .redirectOutput(new File("/dev/tty"))
                    .start()
                    .waitFor();
        } catch (Exception e) {
            DebugLog.log("stty failed: " + e.getMessage());
        }
    }
}
