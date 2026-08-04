package ai.core.cli.ui;

import java.util.Locale;
import java.util.Map;

/**
 * @author xander
 */
public final class AnsiTheme {

    private static final boolean COLOR_ENABLED = System.getenv("NO_COLOR") == null;

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static final Map<String, String> WINDOWS_16_COLOR = Map.ofEntries(
            Map.entry("\u001B[38;5;114m", "\u001B[92m"),  // green -> bright green
            Map.entry("\u001B[38;5;67m", "\u001B[94m"),   // blue -> bright blue
            Map.entry("\u001B[38;5;203m", "\u001B[91m"),  // red -> bright red
            Map.entry("\u001B[38;5;214m", "\u001B[93m"),  // orange -> bright yellow
            Map.entry("\u001B[38;5;245m", "\u001B[90m"),  // gray -> bright black
            Map.entry("\u001B[1;38;5;75m", "\u001B[1;94m"),
            Map.entry("\u001B[38;5;252m", "\u001B[97m"),  // bright white
            Map.entry("\u001B[38;5;216m", "\u001B[93m"),  // light orange -> bright yellow
            Map.entry("\u001B[38;5;251m", "\u001B[97m"),  // light gray -> bright white
            Map.entry("\u001B[38;5;240m", "\u001B[90m"),  // dark gray -> bright black
            Map.entry("\u001B[38;5;177m", "\u001B[95m"),  // purple -> bright magenta
            Map.entry("\u001B[38;5;149m", "\u001B[92m"),  // light green -> bright green
            Map.entry("\u001B[38;5;244m", "\u001B[90m"),  // gray -> bright black
            Map.entry("\u001B[38;5;81m", "\u001B[96m"));  // light blue -> bright cyan

    public static final String RESET = sgr("\u001B[0m");

    // Prompt "User >"
    public static final String PROMPT = sgr("\u001B[38;5;114m");

    // Separator / Thinking
    public static final String SEPARATOR = sgr("\u001B[38;5;67m");

    // Error
    public static final String ERROR = sgr("\u001B[38;5;203m");

    // Tool Approval / Warning
    public static final String WARNING = sgr("\u001B[38;5;214m");

    // Muted (version info, secondary text)
    public static final String MUTED = sgr("\u001B[38;5;245m");

    // Command name (/help etc)
    public static final String CMD_NAME = sgr("\u001B[1;38;5;75m");

    // Command description
    public static final String CMD_DESC = sgr("\u001B[38;5;252m");

    // Markdown header (bold + blue)
    public static final String MD_HEADER = sgr("\u001B[1;38;5;75m");
    public static final String MD_H1 = sgr("\u001B[1;38;5;75m");
    public static final String MD_H2 = sgr("\u001B[1;38;5;114m");
    public static final String MD_H3 = sgr("\u001B[1;38;5;216m");
    public static final String MD_H4 = sgr("\u001B[1m");

    // Inline code
    public static final String MD_INLINE_CODE = sgr("\u001B[38;5;216m");

    // Code block content
    public static final String MD_CODE_BLOCK = sgr("\u001B[38;5;251m");

    // Bold
    public static final String MD_BOLD = sgr("\u001B[1m");

    // Italic
    public static final String MD_ITALIC = sgr("\u001B[3m");

    // List bullet
    public static final String MD_BULLET = sgr("\u001B[38;5;67m");

    // Table border (box-drawing chars)
    public static final String MD_TABLE_BORDER = sgr("\u001B[38;5;240m");

    // Reasoning (dim)
    public static final String REASONING = sgr("\u001B[2m");

    // Success
    public static final String SUCCESS = sgr("\u001B[38;5;114m");

    // Syntax highlighting
    public static final String SYN_KEYWORD = sgr("\u001B[38;5;177m");
    public static final String SYN_STRING = sgr("\u001B[38;5;149m");
    public static final String SYN_COMMENT = sgr("\u001B[38;5;244m");
    public static final String SYN_NUMBER = sgr("\u001B[38;5;216m");
    public static final String SYN_TYPE = sgr("\u001B[38;5;81m");
    public static final String SYN_ANNOTATION = sgr("\u001B[38;5;214m");
    public static final String SYN_DIFF_ADD = sgr("\u001B[38;5;114m");
    public static final String SYN_DIFF_DEL = sgr("\u001B[38;5;203m");

    public static boolean isColorEnabled() {
        return COLOR_ENABLED;
    }

    private static String sgr(String code) {
        if (!COLOR_ENABLED) return "";
        if (WINDOWS) return WINDOWS_16_COLOR.getOrDefault(code, code);
        return code;
    }

    public static String prompt(String text) {
        return PROMPT + text + RESET;
    }

    public static String error(String text) {
        return ERROR + text + RESET;
    }

    public static String warning(String text) {
        return WARNING + text + RESET;
    }

    public static String muted(String text) {
        return MUTED + text + RESET;
    }

    public static String bold(String text) {
        return MD_BOLD + text + RESET;
    }

    public static int displayWidth(String text) {
        int width = 0;
        boolean inEscape = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);

            if (c == '\033') {
                inEscape = true;
                i++;
                continue;
            }
            if (inEscape) {
                if (c == 'm') inEscape = false;
                i++;
                continue;
            }

            // zero-width: variation selectors, ZWJ, combining enclosing keycap
            if (c == '️' || c == '︎' || c == '‍' || c == '⃣') {
                i++;
                continue;
            }

            // surrogate pair → supplementary codepoint (emoji, symbols) → width 2
            if (Character.isHighSurrogate(c) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                width += 2;
                i += 2;
                continue;
            }

            width += isWideChar(c) ? 2 : 1;
            i++;
        }
        return width;
    }

    public static boolean isWideChar(char c) {
        return c >= 0x4E00 && c <= 0x9FFF   // CJK Unified Ideographs
                || c >= 0x3000 && c <= 0x303F  // CJK Symbols and Punctuation
                || c >= 0xFF00 && c <= 0xFFEF  // Halfwidth and Fullwidth Forms
                || c >= 0xAC00 && c <= 0xD7AF  // Hangul Syllables
                || c >= 0x2600 && c <= 0x27BF  // Misc Symbols + Dingbats (☁ ✅ ❌ ✈ …)
                || c >= 0x2B00 && c <= 0x2BFF;  // Misc Symbols and Arrows (⭐ …)
    }

    private AnsiTheme() {
    }
}
