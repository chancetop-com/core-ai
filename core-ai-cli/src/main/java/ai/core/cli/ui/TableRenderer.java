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
        int[] aligns = null;
        int headerIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            String row = rows.get(i).trim();
            if (isSeparator(row)) {
                headerIndex = i;
                aligns = parseAlignment(row);
                continue;
            }
            parsed.add(parseCells(row));
        }
        if (parsed.isEmpty()) {
            return "";
        }
        int cols = maxColumns(parsed);
        if (aligns == null) {
            aligns = new int[cols];
        } else if (aligns.length < cols) {
            int[] padded = new int[cols];
            System.arraycopy(aligns, 0, padded, 0, aligns.length);
            aligns = padded;
        }
        int[] widths = columnWidths(parsed, cols);
        var sb = new StringBuilder();
        sb.append(topBorder(widths)).append('\n');
        for (int i = 0; i < parsed.size(); i++) {
            boolean isHeader = i == 0 && headerIndex >= 0;
            sb.append(dataRow(parsed.get(i), widths, cols, aligns, isHeader)).append('\n');
            if (isHeader) {
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

    private static int[] parseAlignment(String row) {
        String inner = row;
        if (inner.startsWith("|")) inner = inner.substring(1);
        if (inner.endsWith("|")) inner = inner.substring(0, inner.length() - 1);
        String[] parts = inner.split("\\|", -1);
        int[] aligns = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            boolean left = p.startsWith(":");
            boolean right = p.endsWith(":");
            if (left && right) aligns[i] = 1;
            else if (right) aligns[i] = 2;
        }
        return aligns;
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
                String rendered = MarkdownLineRenderer.renderInline(row[i]);
                widths[i] = Math.max(widths[i], AnsiTheme.displayWidth(rendered));
            }
        }
        for (int i = 0; i < cols; i++) {
            widths[i] = Math.max(widths[i], 2);
        }
        return widths;
    }

    private static String pad(String text, int targetWidth, int align) {
        int current = AnsiTheme.displayWidth(text);
        int needed = targetWidth - current;
        if (needed <= 0) return text;
        return switch (align) {
            case 2 -> " ".repeat(needed) + text;
            case 1 -> " ".repeat(needed / 2) + text + " ".repeat(needed - needed / 2);
            default -> text + " ".repeat(needed);
        };
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
    private static String dataRow(String[] cells, int[] widths, int cols, int[] aligns, boolean isHeader) {
        var sb = new StringBuilder();
        sb.append(TC).append('│').append(R);
        for (int i = 0; i < cols; i++) {
            String cell = i < cells.length ? cells[i] : "";
            String rendered = MarkdownLineRenderer.renderInline(cell);
            if (isHeader) {
                rendered = AnsiTheme.MD_BOLD.concat(rendered).concat(AnsiTheme.RESET);
            }
            sb.append(' ').append(pad(rendered, widths[i], aligns[i])).append(' ');
            sb.append(TC).append('│').append(R);
        }
        return sb.toString();
    }

    private TableRenderer() {
    }
}
