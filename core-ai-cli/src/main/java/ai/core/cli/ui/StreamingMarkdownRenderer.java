package ai.core.cli.ui;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * @author xander
 */
public class StreamingMarkdownRenderer {

    private static final String FENCE = "```";
    private static final String ANSI_CLEAR_LINE = "\u001B[2K";
    private static final String ANSI_CURSOR_UP = "\u001B[1A";

    private final PrintWriter writer;
    private final boolean smartTerminal;
    private final int terminalWidth;
    private final StringBuilder buffer = new StringBuilder();
    private final List<String> tableBuffer = new ArrayList<>();
    private boolean inCodeBlock;
    private int printedLength;
    private int printedDisplayWidth;

    public StreamingMarkdownRenderer(PrintWriter writer, boolean smartTerminal, int terminalWidth) {
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

    public void reset() {
        buffer.setLength(0);
        tableBuffer.clear();
        inCodeBlock = false;
        printedLength = 0;
        printedDisplayWidth = 0;
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

        if (smartTerminal) {
            renderSmartLine(line);
        } else {
            printDumbDelta(line);
            updateCodeBlockState(line);
        }
        resetBufferState();
        writer.println();
        writer.flush();
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
        int extraLines = printedDisplayWidth / terminalWidth;
        for (int i = 0; i < extraLines; i++) {
            writer.print(ANSI_CLEAR_LINE + ANSI_CURSOR_UP);
        }
        writer.print(ANSI_CLEAR_LINE + "\r");
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
        if (buffer.length() > printedLength) {
            String delta = buffer.substring(printedLength);
            writer.print(inCodeBlock
                    ? AnsiTheme.MD_CODE_BLOCK + delta + AnsiTheme.RESET
                    : delta);
            writer.flush();
            printedDisplayWidth += AnsiTheme.displayWidth(delta);
            printedLength = buffer.length();
        }
    }

    private void printDumbDelta(String line) {
        if (printedLength < line.length()) {
            writer.print(line.substring(printedLength));
        }
    }

    private void updateCodeBlockState(String line) {
        if (line.stripLeading().startsWith(FENCE)) {
            inCodeBlock = !inCodeBlock;
        }
    }

    private void renderSmartLine(String line) {
        String trimmed = line.stripLeading();
        if (trimmed.startsWith(FENCE)) {
            inCodeBlock = !inCodeBlock;
            writer.print(AnsiTheme.MD_CODE_BLOCK + line + AnsiTheme.RESET);
            return;
        }
        if (inCodeBlock) {
            writer.print(AnsiTheme.MD_CODE_BLOCK + line + AnsiTheme.RESET);
        } else {
            writer.print(MarkdownLineRenderer.renderLine(line));
        }
    }
}
