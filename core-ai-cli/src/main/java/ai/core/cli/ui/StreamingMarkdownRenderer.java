package ai.core.cli.ui;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * @author xander
 */
public class StreamingMarkdownRenderer {

    private static final String FENCE = "```";
    private static final String ANSI_CLEAR_LINE = "\u001B[2K";
    private static final String ANSI_CURSOR_UP = "\u001B[1A";

    private final PrintWriter writer;
    private final boolean smartTerminal;
    private final IntSupplier terminalWidth;
    private final StringBuilder buffer = new StringBuilder();
    private final List<String> tableBuffer = new ArrayList<>();
    private boolean inCodeBlock;
    private String codeBlockLanguage;
    private int printedLength;
    private int printedDisplayWidth;
    private String linePrefix = "";
    private boolean afterFirstLine;
    private int firstLineOffset;

    public StreamingMarkdownRenderer(PrintWriter writer, boolean smartTerminal, IntSupplier terminalWidth) {
        this.writer = writer;
        this.smartTerminal = smartTerminal;
        this.terminalWidth = terminalWidth;
    }

    public void processChunk(String chunk) {
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (c == '\n') {
                completeLine();
            } else {
                buffer.append(c);
            }
        }
        flushPartial();
    }

    public void flush() {
        flushTable();
        if (!buffer.isEmpty()) {
            completeLine();
        }
    }

    public void setLinePrefix(String prefix) {
        this.linePrefix = prefix;
    }

    public void setFirstLineOffset(int offset) {
        this.firstLineOffset = offset;
    }

    public void reset() {
        buffer.setLength(0);
        tableBuffer.clear();
        inCodeBlock = false;
        codeBlockLanguage = null;
        printedLength = 0;
        printedDisplayWidth = 0;
        afterFirstLine = false;
        firstLineOffset = 0;
    }

    private void completeLine() {
        String line = buffer.toString();
        clearPartialOutput();

        if (!inCodeBlock && smartTerminal && TableRenderer.isTableRow(line)) {
            tableBuffer.add(line);
            resetBufferState();
            return;
        }

        flushTable();

        // print prefix + full line content
        if (afterFirstLine && !linePrefix.isEmpty()) {
            writer.print(linePrefix);
        }

        if (smartTerminal) {
            renderSmartLine(line);
        } else {
            writer.print(line);
            updateCodeBlockState(line);
        }

        resetBufferState();
        writer.println();
        writer.flush();
        afterFirstLine = true;
    }

    private void resetBufferState() {
        buffer.setLength(0);
        printedLength = 0;
        printedDisplayWidth = 0;
    }

    private void clearPartialOutput() {
        if (!smartTerminal || printedDisplayWidth <= 0) {
            return;
        }
        int totalWidth = printedDisplayWidth + (!afterFirstLine ? firstLineOffset : 0);
        int width = Math.max(terminalWidth.getAsInt(), 1);
        int extraLines = totalWidth / width;
        for (int i = 0; i < extraLines; i++) {
            writer.print(ANSI_CLEAR_LINE + ANSI_CURSOR_UP);
        }
        if (!afterFirstLine && firstLineOffset > 0) {
            writer.print("\r\u001B[" + firstLineOffset + "C\u001B[0K");
        } else {
            writer.print(ANSI_CLEAR_LINE + "\r");
        }
    }

    private void flushTable() {
        if (tableBuffer.isEmpty()) {
            return;
        }
        String rendered = TableRenderer.render(tableBuffer);
        writer.print(rendered);
        writer.println();
        writer.flush();
        tableBuffer.clear();
    }

    private void flushPartial() {
        if (buffer.length() <= printedLength) {
            return;
        }
        // at start of a new line, print prefix first
        if (printedLength == 0 && afterFirstLine && !linePrefix.isEmpty()) {
            writer.print(linePrefix);
            printedDisplayWidth += AnsiTheme.displayWidth(linePrefix);
        }
        String delta = buffer.substring(printedLength);
        writer.print(inCodeBlock
                ? AnsiTheme.MD_CODE_BLOCK + delta + AnsiTheme.RESET
                : delta);
        writer.flush();
        printedDisplayWidth += AnsiTheme.displayWidth(delta);
        printedLength = buffer.length();
    }

    private void updateCodeBlockState(String line) {
        String trimmed = line.stripLeading();
        if (trimmed.startsWith(FENCE)) {
            if (!inCodeBlock) {
                inCodeBlock = true;
                String langTag = trimmed.substring(FENCE.length()).trim();
                codeBlockLanguage = langTag.isEmpty() ? null : langTag.split("\\s+")[0];
            } else {
                inCodeBlock = false;
                codeBlockLanguage = null;
            }
        }
    }

    private void renderSmartLine(String line) {
        String trimmed = line.stripLeading();
        if (trimmed.startsWith(FENCE)) {
            if (!inCodeBlock) {
                inCodeBlock = true;
                String langTag = trimmed.substring(FENCE.length()).trim();
                codeBlockLanguage = langTag.isEmpty() ? null : langTag.split("\\s+")[0];
            } else {
                inCodeBlock = false;
                codeBlockLanguage = null;
            }
            writer.print(AnsiTheme.MD_CODE_BLOCK + line + AnsiTheme.RESET);
            return;
        }
        if (inCodeBlock) {
            writer.print(CodeHighlighter.highlight(codeBlockLanguage, line));
        } else {
            writer.print(MarkdownLineRenderer.renderLine(line));
        }
    }
}
