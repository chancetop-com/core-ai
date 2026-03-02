package ai.core.cli.ui;

import ai.core.api.session.ApprovalDecision;
import ai.core.api.session.SessionStatus;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import org.jline.terminal.Attributes;

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
            registerSlashWidget();
            this.simpleReader = null;
        }
    }

    public void printStreamingChunk(String chunk) {
        writer.print(chunk);
        writer.flush();
    }

    public void printSeparator() {
        writer.println("\n" + AnsiTheme.SEPARATOR + "------------------------------------------------" + AnsiTheme.RESET);
        writer.flush();
    }

    public void showStatus(SessionStatus status) {
        if (status == SessionStatus.RUNNING) {
            writer.println(AnsiTheme.SEPARATOR + "\n[Agent is thinking...]" + AnsiTheme.RESET);
            writer.flush();
        }
    }

    public void showError(String message) {
        writer.println(AnsiTheme.ERROR + "\n[Error] " + AnsiTheme.RESET + message);
        writer.flush();
    }

    public String readInput() {
        if (jlineReader != null) {
            try {
                return jlineReader.readLine(AnsiTheme.PROMPT + "\nUser > " + AnsiTheme.RESET);
            } catch (org.jline.reader.UserInterruptException e) {
                return "/exit";
            } catch (org.jline.reader.EndOfFileException e) {
                return null;
            }
        }
        writer.print("\nUser > ");
        writer.flush();
        try {
            return simpleReader.readLine();
        } catch (IOException e) {
            return null;
        }
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
        writer.println("\u001B[33m\n> " + toolName + "\u001B[0m");
        if (arguments != null && !arguments.isBlank() && !"{}".equals(arguments.trim())) {
            String display = arguments.length() > 200 ? arguments.substring(0, 200) + "..." : arguments;
            writer.println("\u001B[2m  " + display + "\u001B[0m");
        }
        writer.flush();
    }

    public void showToolResult(String toolName, String status) {
        String icon = "success".equals(status) ? "\u001B[32m+" : "\u001B[31m!";
        writer.println(icon + " " + toolName + "\u001B[0m");
        writer.flush();
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

    /**
     * Read a single raw key from the terminal (bypasses line editing).
     * Returns -1 if terminal is not available or read fails.
     */
    public int readRawKey() {
        if (terminal == null) {
            return -1;
        }
        Attributes saved = terminal.enterRawMode();
        try {
            return terminal.reader().read(100);
        } catch (IOException e) {
            return -1;
        } finally {
            terminal.setAttributes(saved);
        }
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
                reader.callWidget(LineReader.COMPLETE_WORD);
            }
            return true;
        });
        reader.getKeyMaps().get(LineReader.MAIN)
                .bind(new Reference(widgetName), "/");
    }
}
