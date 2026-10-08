package ai.core.cli.ui;

import ai.core.tool.DiffGenerator;

import java.io.PrintWriter;

/**
 * @author stephen
 */
class DiffRenderer {
    private static final String INDENT = "  ";

    private final PrintWriter writer;

    DiffRenderer(PrintWriter writer) {
        this.writer = writer;
    }

    void render(DiffGenerator.DiffResult diff) {
        String summary = formatSummary(diff.additions(), diff.deletions());
        writer.println(INDENT + "\u23BF  " + AnsiTheme.MUTED + summary + AnsiTheme.RESET);

        int maxLineNum = diff.lines().stream().mapToInt(DiffGenerator.DisplayLine::lineNumber).max().orElse(0);
        int numWidth = Math.max(String.valueOf(maxLineNum).length(), 3);
        String numFmt = "%" + numWidth + "d";

        for (var line : diff.lines()) {
            String num = String.format(numFmt, line.lineNumber());
            switch (line.tag()) {
                case DELETE -> writer.println(
                        INDENT + "  " + AnsiTheme.SYN_DIFF_DEL + num + " -" + line.content() + AnsiTheme.RESET);
                case INSERT -> writer.println(
                        INDENT + "  " + AnsiTheme.SYN_DIFF_ADD + num + " +" + line.content() + AnsiTheme.RESET);
                default -> writer.println(
                        INDENT + "  " + AnsiTheme.MUTED + num + "  " + line.content() + AnsiTheme.RESET);
            }
        }
    }

    private String formatSummary(int additions, int deletions) {
        if (additions > 0 && deletions > 0) {
            return String.format("Added %d line%s, removed %d line%s",
                    additions, additions > 1 ? "s" : "", deletions, deletions > 1 ? "s" : "");
        } else if (additions > 0) {
            return String.format("Added %d line%s", additions, additions > 1 ? "s" : "");
        } else if (deletions > 0) {
            return String.format("Removed %d line%s", deletions, deletions > 1 ? "s" : "");
        }
        return "No changes";
    }
}
