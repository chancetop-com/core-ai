package ai.core.cli.ui;

import ai.core.cli.DebugLog;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp;

import java.io.PrintWriter;
import java.util.List;

/**
 * @author stephen
 */
final class TerminalPicker {
    private static final String PICK_UP = "up";
    private static final String PICK_DOWN = "down";
    private static final String PICK_ENTER = "enter";
    private static final String PICK_QUIT = "quit";
    private static final String PICK_ESC = "esc";

    private static String escape(String s) {
        if (s == null) return "null";
        var sb = new StringBuilder(s.length() * 2);
        for (char c : s.toCharArray()) {
            sb.append(c < 32 ? String.format("\\x%02x", (int) c) : String.valueOf(c));
        }
        return sb.toString();
    }

    private final Terminal terminal;
    private final PrintWriter writer;
    private int lastRenderedLines;

    TerminalPicker(Terminal terminal, PrintWriter writer) {
        this.terminal = terminal;
        this.writer = writer;
    }

    int pickIndexRaw(List<String> items) {
        int selected = 0;
        int limit = Math.min(items.size(), 10);
        renderPickerList(items, selected, limit);

        KeyMap<String> keyMap = buildPickerKeyMap();
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

    private KeyMap<String> buildPickerKeyMap() {
        KeyMap<String> keyMap = new KeyMap<>();
        try {
            String keyUp = KeyMap.key(terminal, InfoCmp.Capability.key_up);
            String keyDown = KeyMap.key(terminal, InfoCmp.Capability.key_down);
            DebugLog.log("picker keyMap: key_up=" + escape(keyUp) + ", key_down=" + escape(keyDown));
            keyMap.bind(PICK_UP, keyUp);
            keyMap.bind(PICK_DOWN, keyDown);
        } catch (Exception e) {
            DebugLog.log("picker: failed to get terminal key capabilities: " + e.getMessage());
        }
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
        return keyMap;
    }

    private int getTerminalWidth() {
        int w = terminal.getWidth();
        return w > 0 ? w : 80;
    }

    private int displayLines(String text, int prefixLen) {
        int totalLen = prefixLen + text.length();
        int termWidth = getTerminalWidth();
        return Math.max(1, (totalLen + termWidth - 1) / termWidth);
    }

    private void renderPickerList(List<String> items, int selected, int limit) {
        int totalLines = 0;
        for (int i = 0; i < limit; i++) {
            totalLines += displayLines(items.get(i), 3);
        }

        // pre-clear all lines (covers both previous render residue and wrapped lines)
        int linesToClear = Math.max(lastRenderedLines, totalLines);
        for (int i = 0; i < linesToClear; i++) {
            writer.print("\n\u001B[2K");
        }
        writer.print("\u001B[" + linesToClear + "A");

        // render items; terminal wraps naturally onto pre-cleared lines
        for (int i = 0; i < limit; i++) {
            writer.print("\n\u001B[2K");
            if (i == selected) {
                writer.print(AnsiTheme.PROMPT + " ❯ " + AnsiTheme.RESET + items.get(i));
            } else {
                writer.print("   " + AnsiTheme.MUTED + items.get(i) + AnsiTheme.RESET);
            }
        }

        // cursor up by actual display lines (explicit \n count + wrap-induced lines = totalLines)
        writer.print("\u001B[" + totalLines + "A");
        writer.flush();
        lastRenderedLines = totalLines;
    }

    private void clearPickerList(int limit) {
        int linesToClear = Math.max(lastRenderedLines, limit);
        for (int i = 0; i < linesToClear; i++) {
            writer.print("\n\u001B[2K");
        }
        writer.print("\u001B[" + linesToClear + "A");
        writer.flush();
        lastRenderedLines = 0;
    }

}
