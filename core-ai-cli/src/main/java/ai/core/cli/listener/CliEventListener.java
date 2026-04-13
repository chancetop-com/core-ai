package ai.core.cli.listener;

import ai.core.agent.Agent;
import ai.core.api.server.session.AgentSession;
import ai.core.llm.domain.Usage;
import ai.core.api.server.session.ReasoningChunkEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import org.jline.utils.NonBlockingReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author stephen
 */
public class CliEventListener extends BaseEventListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(CliEventListener.class);

    private static final int ESC = 27;

    private static String truncate(String text) {
        if (text == null) return "null";
        return text.length() <= 200 ? text : text.substring(0, 200) + "...(" + text.length() + " chars)";
    }

    private final Agent agent;
    private final AtomicBoolean turnRunning = new AtomicBoolean(false);
    private volatile long turnTokensBefore;
    private volatile long turnInputBefore;
    private volatile long turnOutputBefore;
    private Thread escReaderThread;
    private org.jline.terminal.Attributes savedTerminalAttributes;

    public CliEventListener(TerminalUI ui, AgentSession session, Agent agent) {
        super(ui, session);
        this.agent = agent;
    }

    @Override
    public void prepareTurn() {
        super.prepareTurn();
        turnRunning.set(true);
        var terminal = ui.getTerminal();
        if (terminal != null) {
            savedTerminalAttributes = terminal.getAttributes();
            terminal.enterRawMode();
        }
        var usage = agent.getCurrentTokenUsage();
        turnTokensBefore = usage.getTotalTokens();
        turnInputBefore = usage.getPromptTokens();
        turnOutputBefore = usage.getCompletionTokens();
        var subagentUsage = new Usage();
        agent.getExecutionContext().setTokenCostCallback(subagentUsage::add);
        panel.getSpinner().setStatsSupplier(() -> {
            var u = agent.getCurrentTokenUsage();
            long tokens = u.getTotalTokens() - turnTokensBefore + subagentUsage.getTotalTokens();
            if (tokens == 0) return null;
            long input = u.getPromptTokens() - turnInputBefore + subagentUsage.getPromptTokens();
            long output = u.getCompletionTokens() - turnOutputBefore + subagentUsage.getCompletionTokens();
            var sb = new StringBuilder(64);
            sb.append(String.format("%,d tokens (\u2191 %,d \u2193 %,d)", tokens, input, output));
            int tasks = Math.max(0, getRunTasksCount());
            int tools = getRunTasksToolCount();
            if (tasks > 0) sb.append(" | ").append(tasks).append(tasks == 1 ? " task" : " tasks");
            if (tools > 0) sb.append(" | ").append(tools).append(tools == 1 ? " tool" : " tools");
            return sb.toString();
        });
        startEscReader();
    }

    @Override
    public void waitForTurn() {
        super.waitForTurn();
        turnRunning.set(false);
        stopEscReader();
        var terminal = ui.getTerminal();
        if (terminal != null && savedTerminalAttributes != null) {
            terminal.setAttributes(savedTerminalAttributes);
        }
    }

    @Override
    public void onReasoningChunk(ReasoningChunkEvent event) {
        LOGGER.debug("reasoning chunk: length={}", event.chunk.length());
        super.onReasoningChunk(event);
    }

    @Override
    public void onTextChunk(TextChunkEvent event) {
        LOGGER.debug("text chunk: length={}", event.chunk.length());
        super.onTextChunk(event);
    }

    @Override
    public void onToolStart(ToolStartEvent event) {
        LOGGER.debug("tool start: {} callId={} args={}", event.toolName, event.callId, truncate(event.arguments));
        panel.stopSpinnerIfActive();
        panel.getMarkdownRenderer().flush();
        showSkillHintIfApplicable(event);
        super.onToolStart(event);
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
        LOGGER.debug("tool result: {} callId={} status={} result={}",
                event.toolName, event.callId, event.status, truncate(event.result));
        super.onToolResult(event);
    }

    @Override
    public void onToolApprovalRequest(ToolApprovalRequestEvent event) {
        LOGGER.debug("tool approval request: {} callId={}", event.toolName, event.callId);
        super.onToolApprovalRequest(event);
        LOGGER.debug("tool approval sent: {} callId={}", event.toolName, event.callId);
    }

    private void startEscReader() {
        // Try JLine NonBlockingReader first (works on all platforms including Windows)
        var terminal = ui.getTerminal();
        if (terminal != null && ui.isJLineEnabled()) {
            LOGGER.debug("ESC reader: using JLine, terminal type={}", terminal.getType());
            startJLineEscReader(terminal);
            return;
        }
        LOGGER.debug("ESC reader: JLine not available, terminal={}, jlineEnabled={}", terminal, ui.isJLineEnabled());
        // Fallback to /dev/tty for Unix systems without JLine
        var ttyFile = new File("/dev/tty");
        if (!ttyFile.exists()) return;
        startTtyEscReader(ttyFile);
    }

    private void startJLineEscReader(org.jline.terminal.Terminal terminal) {
        escReaderThread = new Thread(() -> {
            try {
                var reader = terminal.reader();
                LOGGER.debug("ESC reader: started, reader class={}", reader.getClass().getName());
                while (turnRunning.get() && !Thread.currentThread().isInterrupted()) {
                    int ch = reader.read(50L);
                    if (ch == ESC) {
                        // Check if it's a standalone ESC or part of an escape sequence.
                        // A standalone ESC means the user pressed the Escape key.
                        // Escape sequences (like arrow keys) start with ESC followed by
                        // additional bytes quickly. We peek at the next byte with a short
                        // timeout to distinguish.
                        int next = reader.read(50L);
                        if (next == NonBlockingReader.READ_EXPIRED) {
                            // No more bytes within the timeout — this is a standalone ESC press
                            LOGGER.debug("ESC pressed (jline), cancelling turn");
                            session.cancelTurn();
                            return;
                        }
                        // It was part of an escape sequence, drain the rest
                        drainEscapeSequence(reader, next);
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("jline esc reader error: {}", e.getMessage());
            }
        }, "esc-reader");
        escReaderThread.setDaemon(true);
        escReaderThread.start();
    }

    private void drainEscapeSequence(NonBlockingReader reader, int firstByte) {
        // An escape sequence is typically ESC [ <params> <final_byte>
        // Drain remaining bytes with a short timeout until we get the final byte
        // or until no more bytes are available
        try {
            int ch = firstByte;
            while (ch != NonBlockingReader.EOF) {
                // CSI sequences: ESC [ ... final byte in 0x40-0x7E
                // SS3 sequences: ESC O <final byte>
                if (ch >= 0x40 && ch <= 0x7E) {
                    break; // final byte of escape sequence
                }
                ch = reader.read(20L);
            }
        } catch (Exception e) {
            LOGGER.trace("Failed to drain escape sequence: {}", e.getMessage());
        }
    }

    private void startTtyEscReader(File ttyFile) {
        escReaderThread = new Thread(() -> {
            stty("-icanon", "-echo");
            boolean escPressed = pollEscKey(ttyFile);
            stty("sane");
            if (escPressed) {
                LOGGER.debug("ESC pressed (tty), cancelling turn");
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
            LOGGER.debug("esc reader error: {}", e.getMessage());
        }
        return false;
    }

    private void stopEscReader() {
        if (escReaderThread != null) {
            escReaderThread.interrupt();
            escReaderThread = null;
        }
        if (new File("/dev/tty").exists()) {
            stty("sane");
        }
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
            LOGGER.debug("stty failed: {}", e.getMessage());
        }
    }
}
