package ai.core.cli.ui;

import ai.core.api.server.session.PlanUpdateEvent;
import ai.core.tool.DiffGenerator;
import ai.core.utils.JsonUtil;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author lim chen
 * date: 2026/03/16
 * description: Unified control of output indentation within all turns
 *
 */
public class OutputPanel {
    private static final String INDENT = "  ";

    @SuppressWarnings("unchecked")
    static String formatToolSummary(String toolName, String arguments) {
        if (arguments == null || arguments.isBlank() || "{}".equals(arguments.trim())) {
            return toolName;
        }
        try {
            Map<String, Object> argsMap = JsonUtil.fromJson(Map.class, arguments);
            String summary = extractPrimaryArgs(argsMap);
            if (summary == null) return toolName;
            if (summary.length() > 100) summary = summary.substring(0, 100) + "...";
            return toolName + "(" + summary + ")";
        } catch (Exception e) {
            return toolName;
        }
    }

    static String parseErrorHint(String message) {
        if (message == null) return "Oops, something went wrong.";
        if (message.contains("statusCode=401"))
            return "API key is invalid or expired. Please check your config with /help.";
        if (message.contains("statusCode=402"))
            return "API quota used up. Top up your account or switch model with /model.";
        if (message.contains("statusCode=403"))
            return "No permission to access this model. Try a different one with /model.";
        if (message.contains("statusCode=404")) return "Model not found. Check spelling or try /model to switch.";
        if (message.contains("statusCode=429")) return "Too many requests. Wait a moment and try again.";
        if (message.contains("statusCode=500"))
            return "API server error. This is not your fault \u2014 try again shortly.";
        if (message.contains("statusCode=503")) return "API service is temporarily down. Please try again later.";
        if (message.contains("timeout") || message.contains("Timeout"))
            return "Request timed out. Check your network or try again.";
        if (message.contains("Connection refused")) return "Cannot connect to API. Check your network and config.";
        return message.length() > 80 ? message.substring(0, 77) + "..." : message;
    }

    static String truncateError(String message) {
        return message.length() > 200 ? message.substring(0, 197) + "..." : message;
    }

    private static final int DEFAULT_SUMMARY_ARG_COUNT = 3;

    private static String extractPrimaryArgs(Map<String, Object> argsMap) {
        return extractPrimaryArgs(argsMap, DEFAULT_SUMMARY_ARG_COUNT);
    }

    private static String extractPrimaryArgs(Map<String, Object> argsMap, int maxArgs) {
        if (argsMap.isEmpty()) return null;
        if (argsMap.size() == 1) {
            return String.valueOf(argsMap.values().iterator().next());
        }
        var sb = new StringBuilder();
        var entries = argsMap.entrySet().stream().limit(maxArgs).iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        if (argsMap.size() > maxArgs) {
            sb.append(" ...");
        }
        return sb.toString();
    }

    private final PrintWriter writer;
    private final StreamingMarkdownRenderer mdRenderer;
    private final ThinkingSpinner spinner;
    private final AtomicBoolean spinnerActive = new AtomicBoolean(false);
    private final java.util.function.IntSupplier terminalWidth;

    private volatile boolean textStarted;
    private volatile boolean reasoningShown;
    private volatile String activeTaskName;

    public OutputPanel(PrintWriter writer, boolean smartTerminal, java.util.function.IntSupplier terminalWidth) {
        this.writer = writer;
        this.mdRenderer = new StreamingMarkdownRenderer(writer, smartTerminal, terminalWidth);
        this.spinner = new ThinkingSpinner(writer, terminalWidth);
        this.terminalWidth = terminalWidth;
    }

    public void beginTurn() {
        textStarted = false;
        reasoningShown = false;
        activeTaskName = null;
        mdRenderer.reset();
        spinner.resetTimer();
    }

    public void enterTask(String taskName) {
        this.activeTaskName = taskName;
        textStarted = false;
        reasoningShown = false;
    }

    public void exitTask() {
        this.activeTaskName = null;
        textStarted = false;
        reasoningShown = false;
    }

    public boolean isInTask() {
        return activeTaskName != null;
    }

    public void streamText(String chunk) {
        if (isInTask()) return;
        stopSpinnerIfActive();
        if (!textStarted) {
            textStarted = true;
            String prefix = reasoningShown ? "\n\n" : "\n";
            String icon = AnsiTheme.SEPARATOR + "\u25CF" + AnsiTheme.RESET + " ";
            writer.print(prefix + icon);
            writer.flush();
            mdRenderer.setFirstLineOffset(AnsiTheme.displayWidth("\u25CF "));
        }
        mdRenderer.processChunk(indentAfterNewline(chunk));
    }

    public void streamReasoning(String chunk) {
        if (isInTask()) return;
        stopSpinnerIfActive();
        if (!reasoningShown) {
            reasoningShown = true;
            if (textStarted) {
                mdRenderer.flush();
            }
            writer.print("\n" + AnsiTheme.MUTED + "\u25CF" + AnsiTheme.SYN_NUMBER + " Thinking: " + AnsiTheme.RESET);
            writer.flush();
        }
        writer.print(AnsiTheme.MUTED + AnsiTheme.REASONING + indentAfterNewline(chunk) + AnsiTheme.RESET);
        writer.flush();
    }

    public void toolStart(String toolName, String arguments, String diff) {
        stopSpinnerIfActive();
        mdRenderer.flush();
        String summary = formatToolSummary(toolName, arguments);
        if (isInTask()) {
            writer.println(INDENT + AnsiTheme.MUTED + "\u23BF" + AnsiTheme.RESET + " " + AnsiTheme.MUTED + summary + AnsiTheme.RESET);
        } else {
            writer.println("\n" + AnsiTheme.SEPARATOR + "\u25CF" + AnsiTheme.RESET + " " + summary);
            var diffResult = DiffGenerator.DiffResult.deserialize(diff);
            if (diffResult != null) {
                renderDiff(diffResult);
            }
        }
        writer.flush();
        resetShown();
        startSpinner();

    }

    public void asyncTaskLaunched(String summary) {
        stopSpinnerIfActive();
        writer.print(INDENT + AnsiTheme.SUCCESS + "\u23BF" + AnsiTheme.RESET + "  ");
        writer.println(AnsiTheme.MUTED + summary + AnsiTheme.RESET);
        writer.flush();
        resetShown();
        startSpinner();
    }

    public void startAttributedTaskSection(String taskId, String description) {
        stopSpinnerIfActive();
        mdRenderer.flush();
        var label = description != null && !description.isBlank()
            ? taskId + " · " + description : taskId;
        writer.println("\n" + INDENT + AnsiTheme.MUTED + "\u25B7 " + label + AnsiTheme.RESET);
        writer.flush();
        activeTaskName = taskId;
        resetShown();
        startSpinner();
    }

    public void toolResult(String status, String result) {
        if (isInTask()) return;
        stopSpinnerIfActive();
        String icon = "success".equals(status) ? AnsiTheme.SUCCESS : AnsiTheme.ERROR;
        writer.print(INDENT + icon + "\u23BF" + AnsiTheme.RESET + "  ");
        if (result != null && !result.isBlank()) {
            String[] lines = result.split("\n");
            int limit = Math.min(lines.length, 3);
            for (int i = 0; i < limit; i++) {
                String line = lines[i].stripLeading();
                if (line.length() > 120) line = line.substring(0, 120) + "...";
                if (i > 0) writer.print(INDENT + "   ");
                writer.println(AnsiTheme.MUTED + line + AnsiTheme.RESET);
            }
            if (lines.length > 3) {
                writer.println(AnsiTheme.MUTED + INDENT + "   \u2026 +" + (lines.length - 3) + " lines" + AnsiTheme.RESET);
            }
        } else {
            writer.println(AnsiTheme.MUTED + "Done" + AnsiTheme.RESET);
        }
        writer.flush();
        resetShown();
        startSpinner();

    }

    private void resetShown() {
        reasoningShown = false;
        textStarted = false;
    }

    private void renderDiff(DiffGenerator.DiffResult diff) {
        String summary = formatDiffSummary(diff.additions(), diff.deletions());
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

    private String formatDiffSummary(int additions, int deletions) {
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

    public void error(String message) {
        stopSpinnerIfActive();
        mdRenderer.flush();
        mdRenderer.reset();
        String hint = parseErrorHint(message);
        writer.println("\n" + INDENT + AnsiTheme.ERROR + "\u2717 " + hint + AnsiTheme.RESET);
        if (!hint.equals(message)) {
            writer.println(AnsiTheme.MUTED + INDENT + "  " + truncateError(message) + AnsiTheme.RESET);
        }
        writer.flush();
    }

    public void cancelled() {
        writer.println("\n" + INDENT + AnsiTheme.WARNING + "[Cancelled]" + AnsiTheme.RESET);
        writer.flush();
    }

    public void maxTurnsReached() {
        writer.println("\n" + INDENT + AnsiTheme.WARNING
                + "[Max turns reached] The agent has used all available turns. You can continue the conversation with a follow-up message."
                + AnsiTheme.RESET);
        writer.flush();
    }

    public void turnSummary(long elapsedMs, Long inputTokens, Long outputTokens) {
        var sb = new StringBuilder();
        sb.append('\n').append(AnsiTheme.MUTED).append(INDENT).append("\u2726 ")
                .append(ThinkingSpinner.formatElapsed(elapsedMs));
        if (inputTokens != null && outputTokens != null) {
            long total = inputTokens + outputTokens;
            sb.append(String.format(" | %,d tokens (\u2191 %,d \u2193 %,d)", total, inputTokens, outputTokens));
        }
        sb.append(AnsiTheme.RESET);
        writer.println(sb.toString());
        writer.flush();
    }

    public void planUpdate(List<PlanUpdateEvent.TodoItem> todos) {
        if (todos == null || todos.isEmpty()) return;
        stopSpinnerIfActive();
        mdRenderer.flush();

        writer.println("\n" + AnsiTheme.CMD_NAME + "\u25CF Planning:" + AnsiTheme.RESET);

        // Calculate column widths
        int statusWidth = 13; // "IN PROGRESS"
        int contentWidth = Math.max(20, (terminalWidth.getAsInt() / 2) - statusWidth - 6);

        printHeader(statusWidth, contentWidth);

        // Rows
        for (var todo : todos) {
            String statusIcon = switch (todo.status) {
                case "COMPLETED" -> AnsiTheme.SUCCESS + "\u2713";
                case "IN_PROGRESS" -> AnsiTheme.WARNING + "\u25B6";
                default -> AnsiTheme.MUTED + "\u25CB";
            };
            String statusText = statusIcon + " " + formatStatus(todo.status) + AnsiTheme.RESET;
            String content = wrapContent(todo.content, contentWidth);

            String[] contentLines = content.split("\n");
            for (int i = 0; i < contentLines.length; i++) {
                writer.print(INDENT + AnsiTheme.MD_TABLE_BORDER + "\u2502");
                if (i == 0) {
                    writer.printf(" %-13s ", statusText);
                } else {
                    writer.print(" ".repeat(statusWidth + 2));
                    writer.print(" ");
                }
                writer.print("\u2502");
                writer.printf(" %-" + contentWidth + "s ", contentLines[i]);
                writer.println("\u2502" + AnsiTheme.RESET);
            }
        }

        printFooter(statusWidth, contentWidth);

        writer.flush();
        resetShown();
        startSpinner();
    }

    private void printFooter(int statusWidth, int contentWidth) {
        // Footer
        writer.print(INDENT + AnsiTheme.MD_TABLE_BORDER + "\u2570");
        writer.print("\u2500".repeat(statusWidth + 2));
        writer.print("\u2534");
        writer.print("\u2500".repeat(contentWidth + 2));
        writer.println("\u256F" + AnsiTheme.RESET);
    }

    private void printHeader(int statusWidth, int contentWidth) {

        // Header
        writer.print(INDENT + AnsiTheme.MD_TABLE_BORDER + "\u256D");
        writer.print("\u2500".repeat(statusWidth + 2));
        writer.print("\u252C");
        writer.print("\u2500".repeat(contentWidth + 2));
        writer.println("\u256E" + AnsiTheme.RESET);

        writer.print(INDENT + AnsiTheme.MD_TABLE_BORDER + "\u2502");
        writer.printf(" %-13s ", "STATUS");
        writer.print("\u2502");
        writer.printf(" %-" + contentWidth + "s ", "TASK");
        writer.println("\u2502" + AnsiTheme.RESET);

        writer.print(INDENT + AnsiTheme.MD_TABLE_BORDER + "\u251C");
        writer.print("\u2500".repeat(statusWidth + 2));
        writer.print("\u253C");
        writer.print("\u2500".repeat(contentWidth + 2));
        writer.println("\u2524" + AnsiTheme.RESET);
    }

    private String formatStatus(String status) {
        return switch (status) {
            case "COMPLETED" -> "Done";
            case "IN_PROGRESS" -> "In Progress";
            default -> "Pending";
        };
    }

    private String wrapContent(String content, int width) {
        if (content == null) return "";
        if (content.length() <= width) return content;
        return content.substring(0, width - 3) + "...";
    }

    public void endTurn() {
        stopSpinnerIfActive();
        mdRenderer.flush();
        mdRenderer.reset();
    }

    public ThinkingSpinner getSpinner() {
        return spinner;
    }

    public StreamingMarkdownRenderer getMarkdownRenderer() {
        return mdRenderer;
    }

    public void startSpinner() {
        if (spinnerActive.compareAndSet(false, true)) {
            spinner.start();
        }
    }

    public void stopSpinnerIfActive() {
        if (spinnerActive.compareAndSet(true, false)) {
            spinner.stop();
        }
    }

    private String indentAfterNewline(String text) {
        return text.replace("\n", "\n  ");
    }
}
