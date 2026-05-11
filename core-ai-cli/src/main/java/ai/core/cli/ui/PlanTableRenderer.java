package ai.core.cli.ui;

import ai.core.api.server.session.PlanUpdateEvent;

import java.io.PrintWriter;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * @author lim chen
 */
class PlanTableRenderer {
    private static final String INDENT = "  ";
    private static final int STATUS_WIDTH = 13;

    private static String padToDisplayWidth(String text, int targetWidth) {
        int current = AnsiTheme.displayWidth(text);
        if (current >= targetWidth) return text;
        return text + " ".repeat(targetWidth - current);
    }

    private final PrintWriter writer;
    private final IntSupplier terminalWidth;

    PlanTableRenderer(PrintWriter writer, IntSupplier terminalWidth) {
        this.writer = writer;
        this.terminalWidth = terminalWidth;
    }

    void render(List<PlanUpdateEvent.TodoItem> todos) {
        if (todos == null || todos.isEmpty()) return;

        int contentWidth = Math.max(20, (terminalWidth.getAsInt() / 2) - STATUS_WIDTH - 6);
        printHeader(contentWidth);
        for (var todo : todos) {
            printRow(todo, contentWidth);
        }
        printFooter(contentWidth);
        writer.flush();
    }

    private void printHeader(int contentWidth) {
        writer.print(INDENT + AnsiTheme.MD_TABLE_BORDER + "\u256D");
        writer.print("\u2500".repeat(STATUS_WIDTH + 2));
        writer.print("\u252C");
        writer.print("\u2500".repeat(contentWidth + 2));
        writer.println("\u256E" + AnsiTheme.RESET);

        writer.print(INDENT + AnsiTheme.MD_TABLE_BORDER + "\u2502");
        writer.print(" ");
        writer.print(padToDisplayWidth("STATUS", STATUS_WIDTH));
        writer.print(" ");
        writer.print("\u2502");
        writer.print(" ");
        writer.print(padToDisplayWidth("TASK", contentWidth));
        writer.print(" ");
        writer.println("\u2502" + AnsiTheme.RESET);

        writer.print(INDENT + AnsiTheme.MD_TABLE_BORDER + "\u251C");
        writer.print("\u2500".repeat(STATUS_WIDTH + 2));
        writer.print("\u253C");
        writer.print("\u2500".repeat(contentWidth + 2));
        writer.println("\u2524" + AnsiTheme.RESET);
    }

    private void printFooter(int contentWidth) {
        writer.print(INDENT + AnsiTheme.MD_TABLE_BORDER + "\u2570");
        writer.print("\u2500".repeat(STATUS_WIDTH + 2));
        writer.print("\u2534");
        writer.print("\u2500".repeat(contentWidth + 2));
        writer.println("\u256F" + AnsiTheme.RESET);
    }

    private void printRow(PlanUpdateEvent.TodoItem todo, int contentWidth) {
        String statusIcon = switch (todo.status) {
            case "completed" -> AnsiTheme.SUCCESS + "\u2713";
            case "in_progress" -> AnsiTheme.WARNING + "\u25B6";
            default -> AnsiTheme.MUTED + "\u25CB";
        };
        String statusCore = statusIcon + " " + formatStatus(todo.status);
        String statusText = padToDisplayWidth(statusCore, STATUS_WIDTH) + AnsiTheme.RESET;
        String content = wrapContent(todo.content, contentWidth);

        String[] contentLines = content.split("\n");
        for (int i = 0; i < contentLines.length; i++) {
            writer.print(INDENT + AnsiTheme.MD_TABLE_BORDER + "\u2502");
            if (i == 0) {
                writer.print(" ");
                writer.print(statusText);
                writer.print(" ");
            } else {
                writer.print(" ".repeat(STATUS_WIDTH + 2));
            }
            writer.print("\u2502");
            writer.print(" ");
            writer.print(padToDisplayWidth(contentLines[i], contentWidth));
            writer.print(" ");
            writer.println("\u2502" + AnsiTheme.RESET);
        }
    }

    private String formatStatus(String status) {
        return switch (status) {
            case "completed" -> "Done";
            case "in_progress" -> "In Progress";
            default -> "Pending";
        };
    }

    private String wrapContent(String content, int maxDisplayWidth) {
        if (content == null) return "";
        if (AnsiTheme.displayWidth(content) <= maxDisplayWidth) return content;
        var sb = new StringBuilder();
        int cur = 0;
        int target = maxDisplayWidth - 3;
        int index = 0;
        while (index < content.length()) {
            char c = content.charAt(index);
            boolean surrogate = Character.isHighSurrogate(c) && index + 1 < content.length()
                    && Character.isLowSurrogate(content.charAt(index + 1));
            int w = surrogate || AnsiTheme.isWideChar(c) ? 2 : 1;
            if (cur + w > target) break;
            sb.append(c);
            if (surrogate) {
                sb.append(content.charAt(index + 1));
                index += 2;
            } else {
                index++;
            }
            cur += w;
        }
        return sb + "...";
    }
}
