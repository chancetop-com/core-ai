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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

/**
 * @author stephen
 */
public class TerminalUI {
    private static final Logger LOGGER = LoggerFactory.getLogger(TerminalUI.class);

    private static boolean isDumbTerminal(Terminal t) {
        return t == null || Terminal.TYPE_DUMB.equals(t.getType()) || Terminal.TYPE_DUMB_COLOR.equals(t.getType());
    }

    private static Terminal buildTerminal() throws IOException {
        String os = System.getProperty("os.name", "");
        LOGGER.debug("terminal: os.name={}", os);
        for (String providerName : new String[]{"ffm", "jni", "jansi"}) {
            try {
                Terminal t = TerminalBuilder.builder().system(true).provider(providerName).build();
                if (!isDumbTerminal(t)) {
                    LOGGER.debug("terminal: using provider={}", providerName);
                    return t;
                }
                LOGGER.debug("terminal: provider {} returned dumb terminal, skipping", providerName);
                t.close();
            } catch (Exception e) {
                LOGGER.debug("terminal: provider {} failed: {}", providerName, e.getMessage());
            }
        }
        LOGGER.debug("terminal: all providers failed, falling back to default");
        return TerminalBuilder.builder().system(true).build();
    }

    private static PrintWriter wrapWriter(PrintWriter delegate) {
        String os = System.getProperty("os.name", "");
        if (!os.toLowerCase(Locale.ROOT).contains("win")) return delegate;
        return new PrintWriter(new CrLfFilterWriter(delegate), true);
    }

    private static final String PASTE_END_MARKER = "\u001B[201~";

    private final PrintWriter writer;
    private final LineReader jlineReader;
    private final SlashCommandCompleter slashCompleter;
    private final BufferedReader simpleReader;
    private final PasteBuffer pasteBuffer = new PasteBuffer();
    private Terminal terminal;

    public TerminalUI() {
        initDebugLogging();
        boolean isDumb;
        try {
            this.terminal = buildTerminal();
            isDumb = isDumbTerminal(terminal);
            LOGGER.debug("terminal built: type={}, class={}", terminal.getType(), terminal.getClass().getName());
        } catch (IOException e) {
            LOGGER.warn("terminal build failed: {}", e.getMessage());
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
            // Enable Bracketed Paste Mode for Windows Terminal
            enableBracketedPaste();
        }
    }

    // Bracketed Paste Mode sequences: ESC[?2004h to enable, ESC[?2004l to disable
    private static final String BRACKETED_PASTE_ON = "\u001B[?2004h";
    private static final String BRACKETED_PASTE_OFF = "\u001B[?2004l";

    private void enableBracketedPaste() {
        if (terminal != null) {
            terminal.writer().print(BRACKETED_PASTE_ON);
            terminal.writer().flush();
            LOGGER.debug("bracketed paste mode enabled, sent: {}",
                    BRACKETED_PASTE_ON.replace("\u001B", "ESC"));
        }
    }

    private void disableBracketedPaste() {
        if (terminal != null) {
            terminal.writer().print(BRACKETED_PASTE_OFF);
            terminal.writer().flush();
        }
    }

    // 检测是否有 Bracketed Paste 开始序列到达（ESC[200~）
    private boolean detectBracketedPasteStart() {
        if (terminal == null) return false;
        try {
            var reader = terminal.reader();
            // 非阻塞检查是否有数据
            reader.read(50L);  // 短暂等待
        } catch (Exception e) {
            // ignore
        }
        return false;
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
        LOGGER.debug("status: {}", status);
    }

    public void showError(String message) {
        String hint = OutputPanel.parseErrorHint(message);
        writer.println("\n  " + AnsiTheme.ERROR + "✗ " + hint + AnsiTheme.RESET);
        if (!hint.equals(message)) {
            writer.println(AnsiTheme.MUTED + "    " + OutputPanel.truncateError(message) + AnsiTheme.RESET);
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

    private static final Set<String> EDIT_TOOLS = Set.of("edit_file", "write_file");

    public ApprovalDecision askPermission(String toolName, String arguments, String suggestedPattern) {
        if (EDIT_TOOLS.contains(toolName)) {
            var options = List.of(
                    "Yes",
                    "Yes, allow all edits during this session",
                    "No"
            );
            int choice = pickIndex(options);
            return switch (choice) {
                case 0 -> ApprovalDecision.APPROVE;
                case 1 -> ApprovalDecision.APPROVE_SESSION;
                default -> ApprovalDecision.DENY;
            };
        }
        String displayPattern = truncatePattern(suggestedPattern, 60);
        var options = List.of(
                "Yes",
                "Yes, and don't ask again for: " + displayPattern,
                "No",
                "No, and always deny: " + displayPattern
        );
        int choice = pickIndex(options);
        return switch (choice) {
            case 0 -> ApprovalDecision.APPROVE;
            case 1 -> ApprovalDecision.APPROVE_ALWAYS;
            case 3 -> ApprovalDecision.DENY_ALWAYS;
            default -> ApprovalDecision.DENY;
        };
    }

    static String truncatePattern(String pattern, int limit) {
        if (pattern == null || pattern.length() <= limit) return pattern;
        int paren = pattern.indexOf('(');
        if (paren < 0) return pattern;
        String tool = pattern.substring(0, paren + 1);
        String arg = pattern.substring(paren + 1, pattern.length() - 1);
        int maxArg = limit - tool.length() - 4;
        if (maxArg <= 0) return pattern;
        return tool + arg.substring(0, Math.min(arg.length(), maxArg)) + "..." + ")";
    }

    public void showToolStart(String toolName, String arguments) {
        String summary = OutputPanel.formatToolSummary(toolName, arguments);
        writer.println("\n" + AnsiTheme.SEPARATOR + "\u23FA" + AnsiTheme.RESET + " " + summary);
        writer.flush();
    }

    public void showToolResult(String toolName, String status, String result) {
        String icon = "success".equals(status) ? AnsiTheme.SUCCESS : AnsiTheme.ERROR;
        writer.print("  " + icon + "\u23BF" + AnsiTheme.RESET + "  ");
        if (result != null && !result.isBlank()) {
            String[] lines = result.split("\n");
            int limit = Math.min(lines.length, 3);
            for (int i = 0; i < limit; i++) {
                String line = lines[i];
                if (line.length() > 120) line = line.substring(0, 120) + "...";
                if (i > 0) writer.print("     ");
                writer.println(AnsiTheme.MUTED + line + AnsiTheme.RESET);
            }
            if (lines.length > 3) {
                writer.println(AnsiTheme.MUTED + "     \u2026 +" + (lines.length - 3) + " lines" + AnsiTheme.RESET);
            }
        } else {
            writer.println(AnsiTheme.MUTED + "Done" + AnsiTheme.RESET);
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
            LOGGER.debug("picker: dumb terminal, falling back to numeric selection");
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

    public PasteBuffer getPasteBuffer() {
        return pasteBuffer;
    }

    public void close() throws IOException {
        disableBracketedPaste();
        if (terminal != null) terminal.close();
    }

    private void initDebugLogging() {
        boolean isDebug = DebugLog.isEnabled();
        java.util.logging.Logger.getLogger("org.jline").setLevel(isDebug ? Level.FINE : Level.SEVERE);
        if (isDebug) {
            java.util.logging.Logger.getLogger("org.jline").addHandler(new JLineDebugHandler());
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
        // Enable built-in Bracketed Paste Mode support - relies on terminal sending
        // ESC[200~ (paste start) and ESC[201~ (paste end) sequences
        reader.setOpt(LineReader.Option.BRACKETED_PASTE);
        reader.setOpt(LineReader.Option.AUTO_FRESH_LINE);

        // Check if Bracketed Paste Mode is actually supported by checking terminal type
        String termType = terminal.getType();

        // Windows Terminal should support Bracketed Paste when VTP is enabled
        // Traditional cmd.exe/PowerShell will NOT send these sequences
        boolean supportsBracketedPaste = termType != null &&
                (termType.contains("xterm") || termType.contains("vt100") || termType.contains("vt"));
        LOGGER.debug("bracketed paste: likely supported={} (based on terminal type)", supportsBracketedPaste);

        reader.getKeyMaps().get(LineReader.MAIN)
                .bind(new Reference(LineReader.MENU_COMPLETE), "\t");
        registerSlashWidget(reader);
        return reader;
    }

    private String doReadInput(String promptPrefix) {
        String prompt = promptPrefix != null
                ? AnsiTheme.PROMPT + promptPrefix + AnsiTheme.RESET
                : AnsiTheme.PROMPT + "❯  " + AnsiTheme.RESET;
        if (jlineReader != null) {
            try {
                String line = jlineReader.readLine(prompt);
                if (line == null) return null;

                // On Windows, ConPTY doesn't send bracketed paste sequences.
                // JLine treats pasted newlines as Enter, returning only the first line.
                // After readLine returns, check if more content is already buffered.
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                if (os.contains("win")) {
                    LOGGER.debug("windows input: readLine returned '{}' ({} chars), checking for remaining...",
                            line.length() > 50 ? line.substring(0, 50) + "..." : line, line.length());
                    String remaining = drainRemainingPasteBuffer();
                    if (remaining != null && !remaining.isEmpty()) {
                        String result = line + "\n" + remaining;
                        LOGGER.debug("windows paste: {} + {} = {} chars",
                                line.length(), remaining.length(), result.length());

                        // Echo multi-line paste content with truncation for readability
                        writer.println();
                        String[] allLines = result.split("\n", -1);
                        int maxLines = 5;
                        int terminalWidth = getTerminalWidth() - 4; // account for indent

                        // If remaining content has no newlines but is very long,
                        // it likely contains multiple lines that JLine stripped.
                        // Split by terminal width for display.
                        if (allLines.length <= 2 && remaining.length() > terminalWidth) {
                            writer.println(AnsiTheme.MUTED + "  " + (line.length() > terminalWidth ? line.substring(0, terminalWidth - 3) + "..." : line) + AnsiTheme.RESET);
                            String wrapped = wrapLongLine(remaining, terminalWidth);
                            String[] wrappedLines = wrapped.split("\n", -1);
                            int shown = 0;
                            for (int i = 0; i < Math.min(wrappedLines.length, maxLines) && shown < maxLines; i++) {
                                writer.println(AnsiTheme.MUTED + "  " + wrappedLines[i] + AnsiTheme.RESET);
                                shown++;
                            }
                            int totalLines = estimateLineCount(remaining, terminalWidth) + 1;
                            if (totalLines > maxLines) {
                                writer.println(AnsiTheme.MUTED + "  \u2026 +" + (totalLines - maxLines) + " more lines" + AnsiTheme.RESET);
                            }
                        } else {
                            for (int i = 0; i < Math.min(allLines.length, maxLines); i++) {
                                String displayLine = allLines[i];
                                if (displayLine.length() > terminalWidth) {
                                    displayLine = displayLine.substring(0, terminalWidth - 3) + "...";
                                }
                                writer.println(AnsiTheme.MUTED + "  " + displayLine + AnsiTheme.RESET);
                            }
                            if (allLines.length > maxLines) {
                                writer.println(AnsiTheme.MUTED + "  \u2026 +" + (allLines.length - maxLines) + " more lines" + AnsiTheme.RESET);
                            }
                        }
                        writer.flush();
                        return result;
                    }
                    LOGGER.debug("windows input: no remaining content found");
                }

                return line;
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

    /**
     * Wrap a long string into multiple lines at terminal width,
     * trying to break at word boundaries when possible.
     */
    private String wrapLongLine(String text, int width) {
        if (text.length() <= width) return text;
        StringBuilder sb = new StringBuilder();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + width, text.length());
            if (end < text.length()) {
                // Try to find a space to break at word boundary
                int space = text.lastIndexOf(' ', end);
                if (space > start) {
                    end = space;
                }
            }
            sb.append(text, start, end);
            if (end < text.length()) sb.append('\n');
            start = end;
            // Skip leading spaces for next line
            while (start < text.length() && text.charAt(start) == ' ') start++;
        }
        return sb.toString();
    }

    /**
     * Estimate total visual line count when a long line is wrapped at terminal width.
     */
    private int estimateLineCount(String text, int width) {
        int explicit = text.split("\n", -1).length;
        if (text.length() <= width) return explicit;
        int wrapped = 0;
        for (String line : text.split("\n", -1)) {
            wrapped += Math.max(1, (int) Math.ceil((double) line.length() / width));
        }
        return wrapped;
    }

    /**
     * After JLine readLine returns on Windows, check if there's remaining
     * buffered content from a paste operation. Uses 50ms timeout to catch
     * data that arrives slightly after readLine returns.
     * Returns null if no remaining content found.
     */
    private String drainRemainingPasteBuffer() {
        try {
            var reader = terminal.reader();
            // 50ms timeout: catch data that arrives slightly after readLine returns
            int c = reader.read(50L);
            if (c < 0) {
                LOGGER.debug("drain paste buffer: no content (timeout)");
                return null;
            }

            LOGGER.debug("drain paste buffer: first char = {} (0x{:02x})", c, c);
            StringBuilder sb = new StringBuilder();
            sb.append((char) c);
            while (sb.length() < 500_000) {
                int ch = reader.read(50L);
                if (ch < 0) {
                    LOGGER.debug("drain paste buffer: read timeout after {} chars", sb.length());
                    break;
                }
                if (ch == '\r') continue; // normalize line endings
                sb.append((char) ch);
            }
            String result = sb.toString().trim();
            LOGGER.debug("drain paste buffer: collected {} chars", result.length());
            return result;
        } catch (Exception e) {
            LOGGER.debug("drain paste buffer error: {}", e.getMessage());
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

        impl.getWidgets().put("bracketed-paste", () -> {
            // This widget is triggered when terminal sends ESC[200~ (paste start) sequence
            // The actual pasted content is automatically inserted by JLine's LineReader
            // when BRACKETED_PASTE option is enabled
            LOGGER.debug("bracketed-paste widget triggered (JLine built-in)");
            // Just redraw the line - content is already in buffer
            impl.redrawLine();
            return true;
        });
        impl.getKeyMaps().get(LineReader.MAIN)
                .bind(new Reference("bracketed-paste"), "\u001B[200~");
    }

    private String readBracketedPaste() {
        if (terminal == null) return "";
        StringBuilder sb = new StringBuilder();
        int maxChars = 500_000;
        try {
            var reader = terminal.reader();
            while (sb.length() < maxChars) {
                int c = reader.read(200L);
                if (c < 0) break;
                sb.append((char) c);
                int len = sb.length();
                if (len >= PASTE_END_MARKER.length()
                        && sb.substring(len - PASTE_END_MARKER.length()).equals(PASTE_END_MARKER)) {
                    sb.delete(len - PASTE_END_MARKER.length(), len);
                    break;
                }
            }
        } catch (IOException e) {
            LOGGER.debug("bracketed paste read error: {}", e.getMessage());
        }
        return sb.toString();
    }
}
