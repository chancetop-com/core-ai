package ai.core.cli.ui;

import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Handles paste-related logic for TerminalUI including bracketed paste mode,
 * Windows paste buffer draining, and paste content display.
 *
 * @author xander
 */
class TerminalPasteHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TerminalPasteHandler.class);
    private static final String PASTE_END_MARKER = "\u001B[201~";
    private static final String BRACKETED_PASTE_ON = "\u001B[?2004h";
    private static final String BRACKETED_PASTE_OFF = "\u001B[?2004l";
    private static final int MAX_PASTE_CHARS = 500_000;

    private final Terminal terminal;
    private final PrintWriter writer;

    TerminalPasteHandler(Terminal terminal, PrintWriter writer) {
        this.terminal = terminal;
        this.writer = writer;
    }

    void enableBracketedPaste() {
        if (terminal != null) {
            terminal.writer().print(BRACKETED_PASTE_ON);
            terminal.writer().flush();
        }
    }

    void disableBracketedPaste() {
        if (terminal != null) {
            terminal.writer().print(BRACKETED_PASTE_OFF);
            terminal.writer().flush();
        }
    }

    String handleWindowsPaste(String line, int terminalWidth) {
        LOGGER.debug("windows input: readLine returned ({} chars), checking for remaining...", line.length());
        String remaining = drainRemainingPasteBuffer();
        if (remaining == null || remaining.isEmpty()) {
            LOGGER.debug("windows input: no remaining content found");
            return null;
        }
        String result = line + "\n" + remaining;
        LOGGER.debug("windows paste: {} + {} = {} chars", line.length(), remaining.length(), result.length());
        int lines = (int) result.chars().filter(c -> c == '\n').count() + 1;
        writer.println(AnsiTheme.MUTED + "  [Pasted " + result.length() + " chars, " + lines + " lines]" + AnsiTheme.RESET);
        writer.flush();
        return result;
    }

    String drainRemainingPasteBuffer() {
        try {
            var reader = terminal.reader();
            int c = reader.read(50L);
            if (c < 0) return null;
            LOGGER.debug("drain paste buffer: first char = {} (0x{})", c, Integer.toHexString(c));
            StringBuilder sb = new StringBuilder();
            sb.append((char) c);
            while (sb.length() < MAX_PASTE_CHARS) {
                int ch = reader.read(50L);
                if (ch < 0) break;
                if (ch == '\r') continue;
                sb.append((char) ch);
            }
            String text = sb.toString().trim();
            LOGGER.debug("drain paste buffer: collected {} chars", text.length());
            return text;
        } catch (Exception e) {
            LOGGER.debug("drain paste buffer error: {}", e.getMessage());
            return null;
        }
    }

    String readBracketedPaste() {
        if (terminal == null) return "";
        StringBuilder sb = new StringBuilder();
        try {
            var reader = terminal.reader();
            while (sb.length() < MAX_PASTE_CHARS) {
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

    void registerPasteWidget(LineReader reader, PasteBuffer pasteBuffer) {
        if (!(reader instanceof LineReaderImpl impl)) return;

        impl.getWidgets().put("bracketed-paste", () -> {
            LOGGER.debug("bracketed-paste widget triggered");
            String pasted = readBracketedPaste();
            String normalized = pasted.replace("\r\n", "\n").replace("\r", "\n");
            if (pasteBuffer.isLarge(normalized)) {
                impl.getBuffer().write(pasteBuffer.store(normalized));
            } else {
                impl.getBuffer().write(normalized);
            }
            impl.redrawLine();
            return true;
        });

        impl.getKeyMaps().get(LineReader.MAIN)
                .bind(new Reference("bracketed-paste"), "\u001B[200~");
    }
}
