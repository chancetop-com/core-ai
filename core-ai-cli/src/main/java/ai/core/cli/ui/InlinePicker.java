package ai.core.cli.ui;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.utils.InfoCmp;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Inline picker widget for JLine. Shows a navigable list below the input.
 * Arrow keys navigate, Enter selects, Esc/Backspace/any-other-key cancels.
 *
 * @author xander
 */
public class InlinePicker {

    private static final int MAX_VISIBLE = 10;
    private static final String KEYMAP_NAME = "picker";
    private static final String CLEAR_LINE = "\u001B[2K";
    private static final int PROMPT_DISPLAY_WIDTH = 3; // "❯  " = 3 visible chars

    private final LineReaderImpl reader;
    private final List<String> items = new ArrayList<>();
    private int selectedIndex;
    private String bufferSnapshot;
    private char activeTriggerChar = '@';

    public InlinePicker(LineReaderImpl reader) {
        this.reader = reader;
        registerWidgets();
        registerKeymap();
    }

    public void activate(List<String> candidates, char triggerChar) {
        this.activeTriggerChar = triggerChar;
        items.clear();
        items.addAll(candidates);
        selectedIndex = 0;
        bufferSnapshot = reader.getBuffer().toString();
        renderList();
        reader.setKeyMap(KEYMAP_NAME);
    }

    private void registerWidgets() {
        reader.getWidgets().put("picker-down", () -> {
            if (!items.isEmpty()) {
                selectedIndex = (selectedIndex + 1) % items.size();
                renderList();
            }
            return true;
        });

        reader.getWidgets().put("picker-up", () -> {
            if (!items.isEmpty()) {
                selectedIndex = (selectedIndex - 1 + items.size()) % items.size();
                renderList();
            }
            return true;
        });

        reader.getWidgets().put("picker-select", () -> {
            String selected = items.isEmpty() ? null : items.get(selectedIndex);
            dismiss();
            if (selected != null) {
                int pos = bufferSnapshot.lastIndexOf(activeTriggerChar);
                reader.getBuffer().clear();
                if (pos > 0) {
                    reader.getBuffer().write(bufferSnapshot.substring(0, pos));
                }
                reader.getBuffer().write(selected);
            }
            reader.callWidget(LineReader.REDISPLAY);
            return true;
        });

        reader.getWidgets().put("picker-cancel", () -> {
            dismiss();
            reader.callWidget(LineReader.REDISPLAY);
            return true;
        });

        reader.getWidgets().put("picker-backspace", () -> {
            dismiss();
            if (reader.getBuffer().length() > 0) {
                reader.getBuffer().backspace();
            }
            reader.callWidget(LineReader.REDISPLAY);
            return true;
        });
    }

    private void registerKeymap() {
        KeyMap<Binding> map = new KeyMap<>();
        map.bind(new Reference("picker-down"),
                KeyMap.key(reader.getTerminal(), InfoCmp.Capability.key_down));
        map.bind(new Reference("picker-up"),
                KeyMap.key(reader.getTerminal(), InfoCmp.Capability.key_up));
        map.bind(new Reference("picker-down"), "\t");
        map.bind(new Reference("picker-select"), "\r");
        map.bind(new Reference("picker-select"), "\n");
        map.bind(new Reference("picker-cancel"), "\u001B");
        map.bind(new Reference("picker-backspace"), "\u007F");
        map.bind(new Reference("picker-backspace"), "\b");
        map.bind(new Reference("picker-cancel"), "\u0003"); // Ctrl+C
        map.bind(new Reference("picker-cancel"), "\u0004"); // Ctrl+D
        map.setUnicode(new Reference("picker-cancel"));
        map.setNomatch(new Reference("picker-cancel"));
        reader.getKeyMaps().put(KEYMAP_NAME, map);
    }

    private void dismiss() {
        clearDisplay();
        items.clear();
        reader.setKeyMap(LineReader.MAIN);
    }

    private void renderList() {
        PrintWriter tw = reader.getTerminal().writer();
        int limit = Math.min(items.size(), MAX_VISIBLE);

        for (int i = 0; i < limit; i++) {
            tw.print("\n" + CLEAR_LINE);
            if (i == selectedIndex) {
                tw.print(AnsiTheme.PROMPT + " ❯ " + AnsiTheme.RESET + items.get(i));
            } else {
                tw.print("   " + AnsiTheme.MUTED + items.get(i) + AnsiTheme.RESET);
            }
        }
        moveBackToInput(tw, limit);
    }

    private void clearDisplay() {
        PrintWriter tw = reader.getTerminal().writer();
        int limit = Math.min(items.size(), MAX_VISIBLE);

        for (int i = 0; i < limit; i++) {
            tw.print("\n" + CLEAR_LINE);
        }
        moveBackToInput(tw, limit);
    }

    private void moveBackToInput(PrintWriter tw, int lines) {
        if (lines > 0) {
            tw.print("\u001B[" + lines + "A");
        }
        // move cursor to end of current input (prompt width + buffer length)
        int col = PROMPT_DISPLAY_WIDTH + reader.getBuffer().cursor();
        tw.print("\r");
        if (col > 0) {
            tw.print("\u001B[" + col + "C");
        }
        tw.flush();
    }
}
