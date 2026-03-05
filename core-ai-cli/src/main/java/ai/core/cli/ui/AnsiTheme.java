package ai.core.cli.ui;

/**
 * @author xander
 */
public final class AnsiTheme {

    public static final String RESET = "\u001B[0m";

    // Prompt "User >"
    public static final String PROMPT = "\u001B[38;5;114m";

    // Separator / Thinking
    public static final String SEPARATOR = "\u001B[38;5;67m";

    // Error
    public static final String ERROR = "\u001B[38;5;203m";

    // Tool Approval / Warning
    public static final String WARNING = "\u001B[38;5;214m";

    // Muted (version info, secondary text)
    public static final String MUTED = "\u001B[38;5;245m";

    // Command name (/help etc)
    public static final String CMD_NAME = "\u001B[1;38;5;75m";

    // Command description
    public static final String CMD_DESC = "\u001B[38;5;252m";

    // Markdown header (bold + blue)
    public static final String MD_HEADER = "\u001B[1;38;5;75m";

    // Inline code
    public static final String MD_INLINE_CODE = "\u001B[38;5;216m";

    // Code block content
    public static final String MD_CODE_BLOCK = "\u001B[38;5;251m";

    // Bold
    public static final String MD_BOLD = "\u001B[1m";

    // Italic
    public static final String MD_ITALIC = "\u001B[3m";

    // List bullet
    public static final String MD_BULLET = "\u001B[38;5;67m";

    // Table border (box-drawing chars)
    public static final String MD_TABLE_BORDER = "\u001B[38;5;240m";

    // Reasoning (dim)
    public static final String REASONING = "\u001B[2m";

    // Success
    public static final String SUCCESS = "\u001B[38;5;114m";

    // Syntax highlighting
    public static final String SYN_KEYWORD = "\u001B[38;5;177m";
    public static final String SYN_STRING = "\u001B[38;5;149m";
    public static final String SYN_COMMENT = "\u001B[38;5;244m";
    public static final String SYN_NUMBER = "\u001B[38;5;216m";
    public static final String SYN_TYPE = "\u001B[38;5;81m";
    public static final String SYN_ANNOTATION = "\u001B[38;5;214m";
    public static final String SYN_DIFF_ADD = "\u001B[38;5;114m";
    public static final String SYN_DIFF_DEL = "\u001B[38;5;203m";

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
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF || c >= 0x3000 && c <= 0x303F
                    || c >= 0xFF00 && c <= 0xFFEF || c >= 0xAC00 && c <= 0xD7AF) {
                width += 2;
            } else {
                width++;
            }
        }
        return width;
    }

    private AnsiTheme() {
    }
}
