package ai.core.cli.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * @author xander
 */
public final class TableRenderer {

    private static final String R = AnsiTheme.RESET;
    private static final String TC = AnsiTheme.MD_TABLE_BORDER;

    public static String render(List<String> rows) {
        List<String[]> parsed = new ArrayList<>();
        int headerIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            String row = rows.get(i).trim();
            if (isSeparator(row)) {
                headerIndex = i;
                continue;
            }
            parsed.add(parseCells(row));
        }
        if (parsed.isEmpty()) {
            return "";
        }
        int cols = maxColumns(parsed);
        int[] widths = columnWidths(parsed, cols);
        var sb = new StringBuilder();
        sb.append(topBorder(widths)).append('\n');
        for (int i = 0; i < parsed.size(); i++) {
            sb.append(dataRow(parsed.get(i), widths, cols)).append('\n');
            if (i == 0 && headerIndex > 0) {
                sb.append(middleBorder(widths)).append('\n');
            }
        }
        sb.append(bottomBorder(widths));
        return sb.toString();
    }

    static boolean isTableRow(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 2;
    }

    private static boolean isSeparator(String row) {
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (c != '|' && c != '-' && c != ':' && c != ' ') {
                return false;
            }
        }
        return row.contains("-");
    }

    private static String[] parseCells(String row) {
        String inner = row;
        if (inner.startsWith("|")) inner = inner.substring(1);
        if (inner.endsWith("|")) inner = inner.substring(0, inner.length() - 1);
        String[] parts = inner.split("\\|", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    private static int maxColumns(List<String[]> parsed) {
        int max = 0;
        for (String[] row : parsed) {
            max = Math.max(max, row.length);
        }
        return max;
    }

    private static int[] columnWidths(List<String[]> parsed, int cols) {
        int[] widths = new int[cols];
        for (String[] row : parsed) {
            for (int i = 0; i < row.length && i < cols; i++) {
                widths[i] = Math.max(widths[i], displayWidth(row[i]));
            }
        }
        for (int i = 0; i < cols; i++) {
            widths[i] = Math.max(widths[i], 2);
        }
        return widths;
    }

    private static int displayWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF || c >= 0x3000 && c <= 0x303F
                    || c >= 0xFF00 && c <= 0xFFEF) {
                width += 2;
            } else {
                width++;
            }
        }
        return width;
    }

    private static String pad(String text, int targetWidth) {
        int current = displayWidth(text);
        int needed = targetWidth - current;
        if (needed <= 0) return text;
        return text + " ".repeat(needed);
    }

    private static String topBorder(int[] widths) {
        return border("┌", "┬", "┐", widths);
    }

    private static String middleBorder(int[] widths) {
        return border("├", "┼", "┤", widths);
    }

    private static String bottomBorder(int[] widths) {
        return border("└", "┴", "┘", widths);
    }

    @SuppressWarnings("PMD.ConsecutiveAppendsShouldReuse")
    private static String border(String left, String mid, String right, int[] widths) {
        var sb = new StringBuilder();
        sb.append(TC).append(left);
        for (int i = 0; i < widths.length; i++) {
            sb.append("─".repeat(widths[i] + 2));
            sb.append(i < widths.length - 1 ? mid : right);
        }
        sb.append(R);
        return sb.toString();
    }

    @SuppressWarnings("PMD.ConsecutiveAppendsShouldReuse")
    private static String dataRow(String[] cells, int[] widths, int cols) {
        var sb = new StringBuilder();
        sb.append(TC).append('│').append(R);
        for (int i = 0; i < cols; i++) {
            String cell = i < cells.length ? cells[i] : "";
            sb.append(' ').append(MarkdownLineRenderer.renderInline(pad(cell, widths[i]))).append(' ');
            sb.append(TC).append('│').append(R);
        }
        return sb.toString();
    }

    private TableRenderer() {
    }
}
