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

        // ATX headers: # through ######
        if (line.startsWith("#")) {
            int level = 0;
            while (level < line.length() && level < 6 && line.charAt(level) == '#') {
                level++;
            }
            if (level < line.length() && line.charAt(level) == ' ') {
                String content = line.substring(level + 1);
                return AnsiTheme.MD_HEADER + "#".repeat(level) + " " + content + R;
            }
        }

        // Unordered list: - or * at start
        if (line.startsWith("- ") || line.startsWith("* ")) {
            String bullet = line.substring(0, 1);
            String content = line.substring(2);
            return AnsiTheme.MD_BULLET + bullet + R + " " + renderInline(content);
        }

        // Indented list items
        String stripped = line.stripLeading();
        if (stripped.startsWith("- ") || stripped.startsWith("* ")) {
            int indent = line.length() - stripped.length();
            String bullet = stripped.substring(0, 1);
            String content = stripped.substring(2);
            return " ".repeat(indent) + AnsiTheme.MD_BULLET + bullet + R + " " + renderInline(content);
        }

        // Blockquote: > text
        if (line.startsWith("> ")) {
            String content = line.substring(2);
            return AnsiTheme.MUTED + "> " + R + renderInline(content);
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

    private MarkdownLineRenderer() {
    }
}
