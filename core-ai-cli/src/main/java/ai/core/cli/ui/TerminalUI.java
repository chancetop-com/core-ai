package ai.core.cli.ui;

import ai.core.api.session.ApprovalDecision;
import ai.core.api.session.SessionStatus;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author stephen
 */
public class TerminalUI {
    private final PrintWriter writer;
    private final LineReader jlineReader;
    private final BufferedReader simpleReader;
    private Terminal terminal;

    public TerminalUI() {
        Logger.getLogger("org.jline").setLevel(Level.SEVERE);
        boolean isDumb;
        try {
            this.terminal = TerminalBuilder.builder().system(true).build();
            isDumb = Terminal.TYPE_DUMB.equals(terminal.getType()) || Terminal.TYPE_DUMB_COLOR.equals(terminal.getType());
        } catch (IOException e) {
            isDumb = true;
        }

        if (isDumb) {
            // dumb terminal: JLine's NonBlockingReader is unreliable in GraalVM native-image,
            // bypass LineReader and read stdin directly
            this.writer = terminal != null ? terminal.writer() : new PrintWriter(System.out, true);
            this.jlineReader = null;
            this.simpleReader = new BufferedReader(new InputStreamReader(System.in));
        } else {
            this.writer = terminal.writer();
            this.jlineReader = LineReaderBuilder.builder().terminal(terminal).appName("core-ai").build();
            this.simpleReader = null;
        }
    }

    public void printStreamingChunk(String chunk) {
        writer.print(chunk);
        writer.flush();
    }

    public void printSeparator() {
        writer.println("\n\u001B[36m------------------------------------------------\u001B[0m");
        writer.flush();
    }

    public void showStatus(SessionStatus status) {
        if (status == SessionStatus.RUNNING) {
            writer.println("\u001B[36m\n[Agent is thinking...]\u001B[0m");
            writer.flush();
        }
    }

    public void showError(String message) {
        writer.println("\u001B[31m\n[Error] \u001B[0m" + message);
        writer.flush();
    }

    public String readInput() {
        if (jlineReader != null) {
            try {
                return jlineReader.readLine("\u001B[32m\nUser > \u001B[0m");
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
        writer.println("\u001B[33m\n[Tool Approval Required]\u001B[0m");
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

    public PrintWriter getWriter() {
        return writer;
    }

    public void close() throws IOException {
        if (terminal != null) terminal.close();
    }
}
