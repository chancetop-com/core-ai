package ai.core.cli.ui;

import ai.core.api.session.ApprovalDecision;
import ai.core.api.session.SessionStatus;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author stephen
 */
public class TerminalUI {

    private static boolean isDumbTerminal(Terminal t) {
        return t == null || Terminal.TYPE_DUMB.equals(t.getType()) || Terminal.TYPE_DUMB_COLOR.equals(t.getType());
    }

    private final PrintWriter writer;
    private final LineReader jlineReader;
    private final BufferedReader simpleReader;
    private Terminal terminal;

    public TerminalUI() {
        Logger.getLogger("org.jline").setLevel(Level.SEVERE);
        boolean isDumb;
        try {
            this.terminal = TerminalBuilder.builder().system(true).build();
            isDumb = isDumbTerminal(terminal);
        } catch (IOException e) {
            isDumb = true;
        }

        // when running as subprocess (e.g. Gradle), stdin/stdout are pipes so JLine
        // detects dumb terminal; try /dev/tty to get a real terminal on macOS/Linux
        if (isDumb) {
            isDumb = !tryTtyTerminal();
        }

        if (isDumb) {
            this.writer = terminal != null ? terminal.writer() : new PrintWriter(System.out, true, StandardCharsets.UTF_8);
            this.jlineReader = null;
            this.simpleReader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        } else {
            this.writer = terminal.writer();
            this.jlineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .appName("core-ai")
                    .completer(new SlashCommandCompleter())
                    .build();
            this.jlineReader.setOpt(LineReader.Option.AUTO_LIST);
            this.jlineReader.setOpt(LineReader.Option.AUTO_MENU);
            this.jlineReader.setOpt(LineReader.Option.LIST_PACKED);
            this.jlineReader.setOpt(LineReader.Option.AUTO_MENU_LIST);
            registerSlashWidget();
            this.simpleReader = null;
        }
    }

    public void printStreamingChunk(String chunk) {
        writer.print(chunk);
        writer.flush();
    }

    public void printSeparator() {
        String line = "─".repeat(getTerminalWidth());
        writer.println("\n" + AnsiTheme.SEPARATOR + line + AnsiTheme.RESET);
        writer.flush();
    }

    public void showStatus(SessionStatus status) {
        // spinner is now handled by ThinkingSpinner in CliEventListener
    }

    public void showError(String message) {
        writer.println(AnsiTheme.ERROR + "\n[Error] " + AnsiTheme.RESET + message);
        writer.flush();
    }

    public void printInputFrame() {
        int width = getTerminalWidth();
        String border = AnsiTheme.SEPARATOR + "─".repeat(width) + AnsiTheme.RESET;
        String hint = AnsiTheme.MUTED + "  /help for commands | Ctrl+C to cancel" + AnsiTheme.RESET;
        // top border → prompt placeholder → bottom border → status hint
        writer.println(border);
        writer.println();
        writer.println(border);
        writer.println(hint);
        // move cursor back up to the prompt line (up 3 lines)
        writer.print("\u001B[3A\r");
        writer.flush();
    }

    public String readInput() {
        setBlockCursor();
        try {
            String input = doReadInput();
            clearInputFrameBelow();
            return input;
        } finally {
            restoreCursor();
        }
    }

    private void clearInputFrameBelow() {
        // after readLine, cursor is on the line below prompt (bottom border)
        // clear bottom border + status hint lines
        writer.print("\u001B[2K");
        writer.print("\n\u001B[2K");
        writer.print("\u001B[1A\r");
        writer.flush();
    }

    private String doReadInput() {
        if (jlineReader != null) {
            try {
                return jlineReader.readLine(AnsiTheme.PROMPT + "❯  " + AnsiTheme.RESET);
            } catch (org.jline.reader.UserInterruptException e) {
                return "/exit";
            } catch (org.jline.reader.EndOfFileException e) {
                return null;
            }
        }
        writer.print(AnsiTheme.PROMPT + "❯  " + AnsiTheme.RESET);
        writer.flush();
        try {
            return simpleReader.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private void setBlockCursor() {
        writer.print("\u001B[2 q");
        writer.flush();
    }

    private void restoreCursor() {
        writer.print("\u001B[5 q");
        writer.flush();
    }

    public ApprovalDecision askPermission(String toolName, String arguments) {
        writer.println(AnsiTheme.WARNING + "\n[Tool Approval Required]" + AnsiTheme.RESET);
        writer.println("Tool: " + toolName);
        writer.println("Args: " + arguments);
        writer.flush();

        String input;
        if (jlineReader != null) {
            try {
                input = jlineReader.readLine("Allow this action? (y/n/always): ").trim().toLowerCase(Locale.ROOT);
            } catch (org.jline.reader.UserInterruptException | org.jline.reader.EndOfFileException e) {
                input = "n";
            }
        } else {
            writer.print("Allow this action? (y/n/always): ");
            writer.flush();
            try {
                input = simpleReader.readLine();
                input = input != null ? input.trim().toLowerCase(Locale.ROOT) : "n";
            } catch (IOException e) {
                input = "n";
            }
        }
        return switch (input) {
            case "y", "yes" -> ApprovalDecision.APPROVE;
            case "always", "a" -> ApprovalDecision.APPROVE_ALWAYS;
            default -> ApprovalDecision.DENY;
        };
    }

    public void showToolStart(String toolName, String arguments) {
        writer.println("\n  " + AnsiTheme.WARNING + "⟳ " + AnsiTheme.RESET + toolName);
        if (arguments != null && !arguments.isBlank() && !"{}".equals(arguments.trim())) {
            String display = arguments.length() > 200 ? arguments.substring(0, 200) + "..." : arguments;
            writer.println(AnsiTheme.MUTED + "    " + display + AnsiTheme.RESET);
        }
        writer.flush();
    }

    public void showToolResult(String toolName, String status, String result) {
        String icon = "success".equals(status)
                ? AnsiTheme.SUCCESS + "  ✓ "
                : AnsiTheme.ERROR + "  ✗ ";
        writer.println(icon + AnsiTheme.RESET + toolName);
        if (result != null && !result.isBlank()) {
            String display = result.length() > 300 ? result.substring(0, 300) + "..." : result;
            for (String line : display.split("\n", 5)) {
                writer.println(AnsiTheme.MUTED + "    " + line + AnsiTheme.RESET);
            }
        }
        writer.flush();
    }

    public String readRawLine() {
        if (jlineReader != null) {
            try {
                return jlineReader.readLine();
            } catch (org.jline.reader.UserInterruptException | org.jline.reader.EndOfFileException e) {
                return null;
            }
        }
        try {
            return simpleReader.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    public void endStreaming() {
        writer.println();
        writer.flush();
    }

    public int getTerminalWidth() {
        if (terminal != null) {
            int width = terminal.getWidth();
            if (width > 0) return width;
        }
        return 80;
    }

    public String getTerminalType() {
        return terminal != null ? terminal.getType() : "null";
    }

    public boolean isJLineEnabled() {
        return jlineReader != null;
    }

    public boolean isAnsiSupported() {
        String term = System.getenv("TERM");
        if (term != null && (term.contains("color") || term.contains("xterm") || term.contains("screen"))) {
            return true;
        }
        if (terminal != null && !Terminal.TYPE_DUMB.equals(terminal.getType())) {
            return true;
        }
        return System.console() != null;
    }

    public PrintWriter getWriter() {
        return writer;
    }

    public Terminal getTerminal() {
        return terminal;
    }

    public void close() throws IOException {
        if (terminal != null) terminal.close();
    }

    private boolean tryTtyTerminal() {
        var ttyPath = Path.of("/dev/tty");
        if (!Files.exists(ttyPath)) {
            return false;
        }
        try {
            Terminal oldTerminal = this.terminal;
            this.terminal = TerminalBuilder.builder()
                    .system(false)
                    .streams(Files.newInputStream(ttyPath), Files.newOutputStream(ttyPath))
                    .type("xterm-256color")
                    .name("core-ai")
                    .build();
            if (oldTerminal != null) {
                oldTerminal.close();
            }
            return !isDumbTerminal(this.terminal);
        } catch (IOException e) {
            return false;
        }
    }

    private void registerSlashWidget() {
        if (!(jlineReader instanceof LineReaderImpl reader)) {
            return;
        }
        String widgetName = "slash-auto-complete";
        reader.getWidgets().put(widgetName, () -> {
            reader.callWidget(LineReader.SELF_INSERT);
            if ("/".equals(reader.getBuffer().toString())) {
                reader.callWidget(LineReader.MENU_COMPLETE);
            }
            return true;
        });
        reader.getKeyMaps().get(LineReader.MAIN)
                .bind(new Reference(widgetName), "/");
    }
}
