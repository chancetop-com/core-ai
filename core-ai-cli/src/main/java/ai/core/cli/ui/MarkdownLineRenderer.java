package ai.core.cli.ui;

/**
 * @author xander
 */
public final class MarkdownLineRenderer {

    private static final String R = AnsiTheme.RESET;

    public static String renderLine(String line) {
        if (line.isEmpty()) {
            return line;
        }

        String stripped = line.stripLeading();
        int indent = line.length() - stripped.length();
        String spaces = indent > 0 ? " ".repeat(indent) : "";

        // ATX headers: # through ###### (leading spaces allowed — injected by OutputPanel.indentAfterNewline)
        if (stripped.startsWith("#")) {
            int level = 0;
            while (level < stripped.length() && level < 6 && stripped.charAt(level) == '#') {
                level++;
            }
            if (level < stripped.length() && stripped.charAt(level) == ' ') {
                String content = stripped.substring(level + 1);
                String style = level == 1 ? AnsiTheme.MD_H1
                        : level == 2 ? AnsiTheme.MD_H2
                        : level == 3 ? AnsiTheme.MD_H3
                        : AnsiTheme.MD_H4;
                return spaces + style + renderInline(content) + R;
            }
        }

        // Unordered list items (indented or not)
        if (stripped.startsWith("- ") || stripped.startsWith("* ")) {
            String bullet = stripped.substring(0, 1);
            String content = stripped.substring(2);
            return spaces + AnsiTheme.MD_BULLET + bullet + R + " " + renderInline(content);
        }

        // Thematic break: ---, ***, ___
        if (isThematicBreak(line)) {
            return spaces + AnsiTheme.MUTED + "─".repeat(64) + R;
        }

        // Blockquote: > text
        if (stripped.startsWith("> ")) {
            String content = stripped.substring(2);
            return spaces + AnsiTheme.MUTED + "> " + R + renderInline(content);
        }

        return renderInline(line);
    }

    static String renderInline(String text) {
        var sb = new StringBuilder(text.length() + 64);
        int i = 0;
        int len = text.length();

        while (i < len) {
            char c = text.charAt(i);

            // Inline code: `...`
            if (c == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i) {
                    sb.append(AnsiTheme.MD_INLINE_CODE)
                            .append(text, i + 1, end)
                            .append(R);
                    i = end + 1;
                    continue;
                }
            }

            // Bold: **...**
            if (c == '*' && i + 1 < len && text.charAt(i + 1) == '*') {
                int end = text.indexOf("**", i + 2);
                if (end > i) {
                    sb.append(AnsiTheme.MD_BOLD)
                            .append(text, i + 2, end)
                            .append(R);
                    i = end + 2;
                    continue;
                }
            }

            // Italic: *...*  (single star, not followed by another star)
            if (c == '*' && i + 1 < len && text.charAt(i + 1) != '*') {
                int end = text.indexOf('*', i + 1);
                if (end > i) {
                    sb.append(AnsiTheme.MD_ITALIC)
                            .append(text, i + 1, end)
                            .append(R);
                    i = end + 1;
                    continue;
                }
            }

            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static boolean isThematicBreak(String line) {
        String t = line.strip();
        return t.matches("-{3,}") || t.matches("\\*{3,}") || t.matches("_{3,}");
    }

    private MarkdownLineRenderer() {
    }
}
