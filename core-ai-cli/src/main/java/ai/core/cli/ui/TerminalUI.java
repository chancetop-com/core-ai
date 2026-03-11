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

import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.utils.InfoCmp;

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

    private static final String PICK_UP = "up";
    private static final String PICK_DOWN = "down";
    private static final String PICK_ENTER = "enter";
    private static final String PICK_QUIT = "quit";
    private static final String PICK_ESC = "esc";

    private final PrintWriter writer;
    private final LineReader jlineReader;
    private final BufferedReader simpleReader;
    private Terminal terminal;

    public TerminalUI() {
        boolean isDebug = DebugLog.isEnabled();
        Logger.getLogger("org.jline").setLevel(isDebug ? Level.FINE : Level.SEVERE);
        if (isDebug) {
            // route JLine logs to DebugLog
            Logger.getLogger("org.jline").addHandler(new java.util.logging.Handler() {
                @Override public void publish(java.util.logging.LogRecord record) {
                    DebugLog.log("[jline] " + record.getMessage());
                    if (record.getThrown() != null) {
                        DebugLog.log("[jline] exception: " + record.getThrown());
                    }
                }
                @Override public void flush() {}
                @Override public void close() {}
            });
        }
        boolean isDumb;
        try {
            this.terminal = buildTerminal();
            isDumb = isDumbTerminal(terminal);
            DebugLog.log("terminal built: type=" + terminal.getType() + ", class=" + terminal.getClass().getName());
        } catch (IOException e) {
            DebugLog.log("terminal build failed: " + e.getMessage());
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
            this.writer = wrapWriter(terminal.writer());
            this.jlineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .appName("core-ai")
                    .completer(new org.jline.reader.impl.completer.AggregateCompleter(
                            new SlashCommandCompleter(),
                            new FileReferenceCompleter()
                    ))
                    .build();
            this.jlineReader.setOpt(LineReader.Option.AUTO_LIST);
            this.jlineReader.setOpt(LineReader.Option.AUTO_MENU);
            this.jlineReader.setOpt(LineReader.Option.LIST_PACKED);
            this.jlineReader.getKeyMaps().get(LineReader.MAIN)
                    .bind(new Reference(LineReader.MENU_COMPLETE), "\t");
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
        // handled by ThinkingSpinner in CliEventListener
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
            clearInputFrameBelow();
            return input;
        } finally {
            restoreCursor();
        }
    }

    private void clearInputFrameBelow() {
        writer.flush();
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

    public ApprovalDecision askPermission(String toolName, String arguments) {
        String prompt = "  " + AnsiTheme.WARNING + "? " + AnsiTheme.RESET + "Allow? (y/n/always): ";
        String input;
        if (jlineReader != null) {
            try {
                input = jlineReader.readLine(prompt).trim().toLowerCase(Locale.ROOT);
            } catch (org.jline.reader.UserInterruptException | org.jline.reader.EndOfFileException e) {
                input = "n";
            }
        } else {
            writer.print(prompt);
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
            try {
                @SuppressWarnings("unchecked")
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
            return pickIndexRaw(items);
        } finally {
            terminal.setAttributes(savedAttrs);
        }
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
        }
        return -1;
    }

    private int pickIndexRaw(List<String> items) {
        int selected = 0;
        int limit = Math.min(items.size(), 10);
        renderPickerList(items, selected, limit);

        KeyMap<String> keyMap = new KeyMap<>();
        // use JLine terminal capabilities for cross-platform arrow key support
        try {
            String keyUp = KeyMap.key(terminal, InfoCmp.Capability.key_up);
            String keyDown = KeyMap.key(terminal, InfoCmp.Capability.key_down);
            DebugLog.log("picker keyMap: key_up=" + escape(keyUp) + ", key_down=" + escape(keyDown));
            keyMap.bind(PICK_UP, keyUp);
            keyMap.bind(PICK_DOWN, keyDown);
        } catch (Exception e) {
            DebugLog.log("picker: failed to get terminal key capabilities: " + e.getMessage());
        }
        // also bind standard ANSI sequences as fallback
        keyMap.bind(PICK_UP, "\033[A");
        keyMap.bind(PICK_DOWN, "\033[B");
        keyMap.bind(PICK_UP, "\033OA");
        keyMap.bind(PICK_DOWN, "\033OB");
        keyMap.bind(PICK_ENTER, "\r");
        keyMap.bind(PICK_ENTER, "\n");
        keyMap.bind(PICK_QUIT, "q");
        keyMap.bind(PICK_QUIT, "Q");
        keyMap.bind(PICK_ESC, "\033");
        keyMap.setAmbiguousTimeout(200L);

        DebugLog.log("picker: terminal type=" + terminal.getType() + ", class=" + terminal.getClass().getName());

        var bindingReader = new BindingReader(terminal.reader());
        try {
            while (true) {
                String action = bindingReader.readBinding(keyMap);
                DebugLog.log("picker: action=" + action);
                if (action == null || PICK_QUIT.equals(action) || PICK_ESC.equals(action)) {
                    clearPickerList(limit);
                    return -1;
                }
                if (PICK_ENTER.equals(action)) {
                    clearPickerList(limit);
                    return selected;
                }
                if (PICK_UP.equals(action)) selected = (selected - 1 + limit) % limit;
                if (PICK_DOWN.equals(action)) selected = (selected + 1) % limit;
                renderPickerList(items, selected, limit);
            }
        } catch (Exception e) {
            DebugLog.log("picker: error reading input: " + e.getMessage());
            return -1;
        }
    }

    private static String escape(String s) {
        if (s == null) return "null";
        var sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c < 32) sb.append("\\x").append(String.format("%02x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    private void renderPickerList(List<String> items, int selected, int limit) {
        for (int i = 0; i < limit; i++) {
            writer.print("\n\u001B[2K");
            if (i == selected) {
                writer.print(AnsiTheme.PROMPT + " ❯ " + AnsiTheme.RESET + items.get(i));
            } else {
                writer.print("   " + AnsiTheme.MUTED + items.get(i) + AnsiTheme.RESET);
            }
        }
        writer.print("\u001B[" + limit + "A");
        writer.flush();
    }

    private void clearPickerList(int limit) {
        for (int i = 0; i < limit; i++) {
            writer.print("\n\u001B[2K");
        }
        writer.print("\u001B[" + limit + "A");
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

    public void close() throws IOException {
        if (terminal != null) terminal.close();
    }

    private static Terminal buildTerminal() throws IOException {
        String os = System.getProperty("os.name", "");
        DebugLog.log("terminal: os.name=" + os);
        // try each provider explicitly (GraalVM native image may not discover providers via ServiceLoader)
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

    // on Windows native terminal, \n alone only does LF without CR, causing output to drift right.
    // wrap writer to auto-translate \n to \r\n
    private static PrintWriter wrapWriter(PrintWriter delegate) {
        String os = System.getProperty("os.name", "");
        if (!os.toLowerCase(Locale.ROOT).contains("win")) return delegate;
        return new PrintWriter(new java.io.FilterWriter(delegate) {
            @Override
            public void write(int c) throws IOException {
                if (c == '\n') out.write('\r');
                out.write(c);
            }

            @Override
            public void write(char[] cbuf, int off, int len) throws IOException {
                for (int i = off; i < off + len; i++) {
                    write(cbuf[i]);
                }
            }

            @Override
            public void write(String str, int off, int len) throws IOException {
                for (int i = off; i < off + len; i++) {
                    write(str.charAt(i));
                }
            }
        }, true);
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
        String slashWidget = "slash-auto-complete";
        reader.getWidgets().put(slashWidget, () -> {
            reader.callWidget(LineReader.SELF_INSERT);
            if ("/".equals(reader.getBuffer().toString())) {
                reader.callWidget(LineReader.LIST_CHOICES);
            }
            return true;
        });
        reader.getKeyMaps().get(LineReader.MAIN)
                .bind(new Reference(slashWidget), "/");

        String atWidget = "at-file-complete";
        reader.getWidgets().put(atWidget, () -> {
            reader.callWidget(LineReader.SELF_INSERT);
            String buf = reader.getBuffer().toString();
            if (buf.endsWith("@") && (buf.length() == 1 || buf.charAt(buf.length() - 2) == ' ')) {
                reader.callWidget(LineReader.LIST_CHOICES);
            }
            return true;
        });
        reader.getKeyMaps().get(LineReader.MAIN)
                .bind(new Reference(atWidget), "@");

        reader.getWidgets().put("backspace-refresh", () -> {
            reader.callWidget(LineReader.BACKWARD_DELETE_CHAR);
            org.jline.reader.impl.CompletionHelper.refreshCompletion(reader);
            return true;
        });
        reader.getKeyMaps().get(LineReader.MAIN)
                .bind(new Reference("backspace-refresh"), "\u007F");
        reader.getKeyMaps().get(LineReader.MAIN)
                .bind(new Reference("backspace-refresh"), "\u0008");
    }
}
