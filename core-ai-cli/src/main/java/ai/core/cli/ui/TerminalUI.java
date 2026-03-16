package ai.core.cli.ui;

import ai.core.api.server.session.ApprovalDecision;
import ai.core.api.server.session.SessionStatus;
import ai.core.cli.DebugLog;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import ai.core.utils.JsonUtil;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author stephen
 */
public class TerminalUI {

    private static boolean isDumbTerminal(Terminal t) {
        return t == null || Terminal.TYPE_DUMB.equals(t.getType()) || Terminal.TYPE_DUMB_COLOR.equals(t.getType());
    }

    private static Terminal buildTerminal() throws IOException {
        String os = System.getProperty("os.name", "");
        DebugLog.log("terminal: os.name=" + os);
        for (String providerName : new String[]{"ffm", "jni", "jansi"}) {
            try {
                Terminal t = TerminalBuilder.builder().system(true).provider(providerName).build();
                if (!isDumbTerminal(t)) {
                    DebugLog.log("terminal: using provider=" + providerName);
                    return t;
                }
                DebugLog.log("terminal: provider " + providerName + " returned dumb terminal, skipping");
                t.close();
            } catch (Exception e) {
                DebugLog.log("terminal: provider " + providerName + " failed: " + e.getMessage());
            }
        }
        DebugLog.log("terminal: all providers failed, falling back to default");
        return TerminalBuilder.builder().system(true).build();
    }

    private static PrintWriter wrapWriter(PrintWriter delegate) {
        String os = System.getProperty("os.name", "");
        if (!os.toLowerCase(Locale.ROOT).contains("win")) return delegate;
        return new PrintWriter(new CrLfFilterWriter(delegate), true);
    }

    private final PrintWriter writer;
    private final LineReader jlineReader;
    private final SlashCommandCompleter slashCompleter;
    private final BufferedReader simpleReader;
    private Terminal terminal;

    public TerminalUI() {
        initDebugLogging();
        boolean isDumb;
        try {
            this.terminal = buildTerminal();
            isDumb = isDumbTerminal(terminal);
            DebugLog.log("terminal built: type=" + terminal.getType() + ", class=" + terminal.getClass().getName());
        } catch (IOException e) {
            DebugLog.log("terminal build failed: " + e.getMessage());
            isDumb = true;
        }

        if (isDumb) {
            isDumb = !tryTtyTerminal();
        }

        if (isDumb) {
            this.writer = terminal != null ? terminal.writer() : new PrintWriter(System.out, true, StandardCharsets.UTF_8);
            this.jlineReader = null;
            this.slashCompleter = null;
            this.simpleReader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        } else {
            this.writer = wrapWriter(terminal.writer());
            this.slashCompleter = new SlashCommandCompleter();
            this.jlineReader = buildJLineReader();
            this.simpleReader = null;
        }
    }

    public void setSlashCommands(List<ai.core.cli.command.SlashCommand> commands) {
        if (slashCompleter != null) slashCompleter.setCommands(commands);
    }

    public void resetSlashCommands() {
        if (slashCompleter != null) slashCompleter.resetCommands();
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
        DebugLog.log("status: " + status);
    }

    public void showError(String message) {
        String hint = parseErrorHint(message);
        writer.println("\n  " + AnsiTheme.ERROR + "✗ " + hint + AnsiTheme.RESET);
        if (!hint.equals(message)) {
            writer.println(AnsiTheme.MUTED + "    " + truncateError(message) + AnsiTheme.RESET);
        }
        writer.flush();
    }

    public void printInputFrame() {
        writer.println();
        writer.flush();
    }

    public String readInput() {
        return readInput(null);
    }

    public String readInput(String promptPrefix) {
        setBlockCursor();
        try {
            String input = doReadInput(promptPrefix);
            writer.flush();
            return input;
        } finally {
            restoreCursor();
        }
    }

    public ApprovalDecision askPermission(String toolName, String arguments, String suggestedPattern) {
        writer.println("  " + AnsiTheme.WARNING + "?" + AnsiTheme.RESET + " Allow " + toolName + "?");
        var options = List.of(
                "Yes",
                "Yes, and don't ask again for: " + suggestedPattern,
                "No",
                "No, and always deny: " + suggestedPattern
        );
        int choice = pickIndex(options);
        return switch (choice) {
            case 0 -> ApprovalDecision.APPROVE;
            case 1 -> ApprovalDecision.APPROVE_ALWAYS;
            case 3 -> ApprovalDecision.DENY_ALWAYS;
            default -> ApprovalDecision.DENY;
        };
    }

    public void showToolStart(String toolName, String arguments) {
        writer.println("\n  " + AnsiTheme.WARNING + "⟳ " + AnsiTheme.RESET + toolName);
        if (arguments != null && !arguments.isBlank() && !"{}".equals(arguments.trim())) {
            printToolArguments(arguments);
        }
        writer.flush();
    }

    public void showToolResult(String toolName, String status, String result) {
        String icon = "success".equals(status)
                ? AnsiTheme.SUCCESS + "  ✓ "
                : AnsiTheme.ERROR + "  ✗ ";
        writer.println(icon + AnsiTheme.RESET + toolName);
        if (result != null && !result.isBlank()) {
            String firstLine = result.split("\n", 2)[0];
            if (firstLine.length() > 120) firstLine = firstLine.substring(0, 120) + "...";
            writer.println(AnsiTheme.MUTED + "    " + firstLine + AnsiTheme.RESET);
        }
        writer.flush();
    }

    public String readRawLine() {
        return readRawLine(null);
    }

    public String readRawLine(String prompt) {
        if (jlineReader != null) {
            try {
                return prompt != null ? jlineReader.readLine(prompt) : jlineReader.readLine();
            } catch (org.jline.reader.UserInterruptException | org.jline.reader.EndOfFileException e) {
                return null;
            }
        }
        if (prompt != null) {
            writer.print(prompt);
            writer.flush();
        }
        try {
            return simpleReader.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    public int pickIndex(List<String> items) {
        if (items.isEmpty()) return -1;
        if (terminal == null || isDumbTerminal(terminal)) {
            DebugLog.log("picker: dumb terminal, falling back to numeric selection");
            return pickIndexNumeric(items);
        }
        var savedAttrs = terminal.enterRawMode();
        try {
            return new TerminalPicker(terminal, writer).pickIndexRaw(items);
        } finally {
            terminal.setAttributes(savedAttrs);
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

    private void initDebugLogging() {
        boolean isDebug = DebugLog.isEnabled();
        Logger.getLogger("org.jline").setLevel(isDebug ? Level.FINE : Level.SEVERE);
        if (isDebug) {
            Logger.getLogger("org.jline").addHandler(new JLineDebugHandler());
        }
    }

    private LineReader buildJLineReader() {
        var reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("core-ai")
                .completer(new org.jline.reader.impl.completer.AggregateCompleter(
                        slashCompleter,
                        new FileReferenceCompleter()
                ))
                .build();
        reader.setOpt(LineReader.Option.AUTO_LIST);
        reader.setOpt(LineReader.Option.AUTO_MENU);
        reader.setOpt(LineReader.Option.LIST_PACKED);
        reader.getKeyMaps().get(LineReader.MAIN)
                .bind(new Reference(LineReader.MENU_COMPLETE), "\t");
        registerSlashWidget(reader);
        return reader;
    }

    private String readLineWithPrompt(String prompt) {
        if (jlineReader != null) {
            try {
                return jlineReader.readLine(prompt);
            } catch (org.jline.reader.UserInterruptException | org.jline.reader.EndOfFileException e) {
                return null;
            }
        }
        writer.print(prompt);
        writer.flush();
        try {
            return simpleReader.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private String parseErrorHint(String message) {
        if (message == null) return "Oops, something went wrong.";
        if (message.contains("statusCode=401")) return "API key is invalid or expired. Please check your config with /help.";
        if (message.contains("statusCode=402")) return "API quota used up. Top up your account or switch model with /model.";
        if (message.contains("statusCode=403")) return "No permission to access this model. Try a different one with /model.";
        if (message.contains("statusCode=404")) return "Model not found. Check spelling or try /model to switch.";
        if (message.contains("statusCode=429")) return "Too many requests. Wait a moment and try again.";
        if (message.contains("statusCode=500")) return "API server error. This is not your fault — try again shortly.";
        if (message.contains("statusCode=503")) return "API service is temporarily down. Please try again later.";
        if (message.contains("timeout") || message.contains("Timeout")) return "Request timed out. Check your network or try again.";
        if (message.contains("Connection refused")) return "Cannot connect to API. Check your network and config.";
        return message.length() > 80 ? message.substring(0, 77) + "..." : message;
    }

    private String truncateError(String message) {
        return message.length() > 200 ? message.substring(0, 197) + "..." : message;
    }

    @SuppressWarnings("unchecked")
    private void printToolArguments(String arguments) {
        try {
            Map<String, Object> argsMap = JsonUtil.fromJson(Map.class, arguments);
            for (var entry : argsMap.entrySet()) {
                String value = String.valueOf(entry.getValue());
                if (value.length() > 100) value = value.substring(0, 100) + "...";
                writer.println(AnsiTheme.MUTED + "    " + entry.getKey() + ": " + value + AnsiTheme.RESET);
            }
        } catch (Exception e) {
            String display = arguments.length() > 200 ? arguments.substring(0, 200) + "..." : arguments;
            writer.println(AnsiTheme.MUTED + "    " + display + AnsiTheme.RESET);
        }
    }

    private String doReadInput(String promptPrefix) {
        String prompt = promptPrefix != null
                ? AnsiTheme.PROMPT + promptPrefix + AnsiTheme.RESET
                : AnsiTheme.PROMPT + "❯  " + AnsiTheme.RESET;
        if (jlineReader != null) {
            try {
                return jlineReader.readLine(prompt);
            } catch (org.jline.reader.UserInterruptException e) {
                return "/exit";
            } catch (org.jline.reader.EndOfFileException e) {
                return null;
            }
        }
        writer.print(prompt);
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

    private int pickIndexNumeric(List<String> items) {
        int limit = Math.min(items.size(), 10);
        for (int i = 0; i < limit; i++) {
            writer.println("  " + (i + 1) + ". " + items.get(i));
        }
        writer.print(AnsiTheme.PROMPT + "Enter number (1-" + limit + ", q to cancel): " + AnsiTheme.RESET);
        writer.flush();
        String line = readRawLine();
        if (line == null || line.isBlank() || "q".equalsIgnoreCase(line.trim())) return -1;
        try {
            int choice = Integer.parseInt(line.trim());
            if (choice >= 1 && choice <= limit) return choice - 1;
        } catch (NumberFormatException ignored) {
            // not a valid number, return -1
        }
        return -1;
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

    private void registerSlashWidget(LineReader reader) {
        if (!(reader instanceof LineReaderImpl impl)) {
            return;
        }
        impl.getWidgets().put("slash-auto-complete", () -> {
            impl.callWidget(LineReader.SELF_INSERT);
            if ("/".equals(impl.getBuffer().toString())) {
                impl.callWidget(LineReader.LIST_CHOICES);
            }
            return true;
        });
        impl.getKeyMaps().get(LineReader.MAIN)
                .bind(new Reference("slash-auto-complete"), "/");

        impl.getWidgets().put("at-file-complete", () -> {
            impl.callWidget(LineReader.SELF_INSERT);
            String buf = impl.getBuffer().toString();
            if (buf.endsWith("@") && (buf.length() == 1 || buf.charAt(buf.length() - 2) == ' ')) {
                impl.callWidget(LineReader.LIST_CHOICES);
            }
            return true;
        });
        impl.getKeyMaps().get(LineReader.MAIN)
                .bind(new Reference("at-file-complete"), "@");

        impl.getWidgets().put("backspace-refresh", () -> {
            impl.callWidget(LineReader.BACKWARD_DELETE_CHAR);
            org.jline.reader.impl.CompletionHelper.refreshCompletion(impl);
            return true;
        });
        impl.getKeyMaps().get(LineReader.MAIN)
                .bind(new Reference("backspace-refresh"), "\u007F");
        impl.getKeyMaps().get(LineReader.MAIN)
                .bind(new Reference("backspace-refresh"), "\u0008");
    }
}
