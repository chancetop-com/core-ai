package ai.core.cli.ui;

import ai.core.api.server.session.PlanUpdateEvent;
import ai.core.tool.DiffGenerator;
import ai.core.tool.tools.EditFileTool;
import ai.core.tool.tools.GlobFileTool;
import ai.core.tool.tools.GrepFileTool;
import ai.core.tool.tools.ReadFileTool;
import ai.core.tool.tools.ShellCommandTool;
import ai.core.tool.tools.TaskTool;
import ai.core.tool.tools.WebFetchTool;
import ai.core.tool.tools.WebSearchTool;
import ai.core.tool.tools.WriteFileTool;
import ai.core.utils.JsonUtil;

import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

/**
 * @author lim chen
 * date: 2026/03/16
 * description: Unified control of output indentation within all turns
 *
 */
public class OutputPanel {
    private static final String INDENT = "  ";
    private static final int DEFAULT_SUMMARY_ARG_COUNT = 3;

    @SuppressWarnings("unchecked")
    public static String formatToolSummary(String toolName, String arguments, String model) {
        if (arguments == null || arguments.isBlank() || "{}".equals(arguments.trim())) {
            return convertToolName(toolName);
        }
        try {
            Map<String, Object> argsMap = JsonUtil.fromJson(Map.class, arguments);
            String summary = specialTaskSummary(toolName, argsMap, model);
            if (summary == null) return convertToolName(toolName);
            if (summary.length() > 100) summary = summary.substring(0, 100) + "...";
            return convertToolName(toolName) + "(" + summary + ")";
        } catch (Exception e) {
            return convertToolName(toolName);
        }
    }

    public static String convertToolName(String sourceName) {
        return switch (sourceName) {
            case TaskTool.TOOL_NAME -> "Task";
            case EditFileTool.TOOL_NAME -> "Update";
            case WriteFileTool.TOOL_NAME -> "Write";
            case ReadFileTool.TOOL_NAME -> "Read";
            case GrepFileTool.TOOL_NAME -> "Grep";
            case GlobFileTool.TOOL_NAME -> "Glob";
            case ShellCommandTool.TOOL_NAME -> "Bash";
            case WebFetchTool.TOOL_NAME -> "WebFetch";
            case WebSearchTool.TOOL_NAME -> "WebSearch";
            default -> sourceName;
        };
    }

    static String specialTaskSummary(String toolName, Map<String, Object> argsMap, String model) {
        if (TaskTool.TOOL_NAME.equals(toolName)) {
            var subagentType = argsMap.get("subagent_type");
            var description = argsMap.get("description");
            if (model != null && !model.equals(subagentType)) {
                return "%s[%s]:%s".formatted(subagentType, model, description);
            }
            return "%s:%s".formatted(subagentType, description);
        } else if (ShellCommandTool.TOOL_NAME.equals(toolName)) {
            return (String) argsMap.get("command");
        } else {
            return extractPrimaryArgs(argsMap);
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
        if (message == null) return null;
        return message.length() > 200 ? message.substring(0, 197) + "..." : message;
    }

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
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        if (argsMap.size() > maxArgs) {
            sb.append(" ...");
        }
        return sb.toString();
    }

    private static String truncateToWidth(String text, int maxWidth) {
        return text.length() > maxWidth ? text.substring(0, maxWidth - 3) + "..." : text;
    }

    private final PrintWriter writer;
    private final StreamingMarkdownRenderer mdRenderer;
    private final ThinkingSpinner spinner;
    private final PlanTableRenderer planRenderer;
    private final AtomicBoolean spinnerActive = new AtomicBoolean(false);
    private final IntSupplier terminalWidth;

    private volatile boolean textStarted;
    private volatile boolean reasoningShown;
    private long toolStartTime;
    private int toolOutputLineCount;
    private boolean toolOutputStreaming;

    public OutputPanel(PrintWriter writer, boolean smartTerminal, IntSupplier terminalWidth) {
        this.writer = writer;
        this.mdRenderer = new StreamingMarkdownRenderer(writer, smartTerminal, terminalWidth);
        this.spinner = new ThinkingSpinner(writer, terminalWidth);
        this.planRenderer = new PlanTableRenderer(writer, terminalWidth);
        this.terminalWidth = terminalWidth;
    }

    public void beginTurn() {
        textStarted = false;
        reasoningShown = false;
        mdRenderer.reset();
        spinner.resetTimer();
    }

    public void streamText(String chunk) {
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

    public void batchStart(String group, String summary, boolean frontTask) {
        stopSpinnerIfActive();
        mdRenderer.flush();
        toolStartTime = System.currentTimeMillis();
        toolOutputStreaming = false;
        toolOutputLineCount = 0;
        if (frontTask) {
            writer.println(INDENT + AnsiTheme.MUTED + "⎿" + AnsiTheme.RESET + " " + AnsiTheme.MUTED + group + "(" + summary + ")" + AnsiTheme.RESET);
        } else {
            writer.println("\n" + AnsiTheme.SEPARATOR + "●" + AnsiTheme.RESET + " " + group + "(" + summary + ")");
        }
        writer.flush();
        boolean wasReasoningShown = reasoningShown;
        resetShown();
        startSpinner(wasReasoningShown);
    }

    public void toolStart(String toolName, String arguments, String diff, Boolean frontTask, boolean restartSpinner, String model) {
        stopSpinnerIfActive();
        mdRenderer.flush();
        toolStartTime = System.currentTimeMillis();
        toolOutputStreaming = false;
        toolOutputLineCount = 0;
        String summary = formatToolSummary(toolName, arguments, model);
        if (frontTask) {
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
        if (restartSpinner) {
            boolean wasReasoningShown = reasoningShown;
            startSpinner(wasReasoningShown);
        }

    }

    public void asyncTaskLaunched() {
        stopSpinnerIfActive();
        writer.println(INDENT + AnsiTheme.MUTED + "\u23BF  Running in background" + AnsiTheme.RESET);
        writer.flush();
        boolean wasReasoningShown = reasoningShown;
        resetShown();
        startSpinner(wasReasoningShown);
    }


    public void toolResult(String status, String result) {
        stopSpinnerIfActive();
        String icon = "success".equals(status) ? AnsiTheme.SUCCESS : AnsiTheme.ERROR;

        if (toolOutputStreaming && toolOutputLineCount > 5) {
            clearStatusLine();
        }

        if (toolOutputStreaming && toolOutputLineCount > 0) {
            writer.print(INDENT + icon + "\u23BF" + AnsiTheme.RESET + "  ");
            long elapsed = System.currentTimeMillis() - toolStartTime;
            writer.println(AnsiTheme.MUTED + "Done, " + toolOutputLineCount + " lines, " + ThinkingSpinner.formatElapsed(elapsed) + AnsiTheme.RESET);
        } else {
            writer.print(INDENT + icon + "\u23BF" + AnsiTheme.RESET + "  ");
            printResultSummary(result);
        }
        writer.flush();
        toolOutputStreaming = false;
        boolean wasReasoningShown = reasoningShown;
        resetShown();
        startSpinner(wasReasoningShown);
    }

    public void batchResult(String status, String result) {
        stopSpinnerIfActive();
        if (result != null && !result.isBlank()) {
            String icon = "success".equals(status) ? AnsiTheme.SUCCESS : AnsiTheme.ERROR;
            String[] lines = result.split("\n");
            int limit = Math.min(lines.length, 3);
            int maxWidth = Math.max(40, terminalWidth.getAsInt() - 6);
            for (int i = 0; i < limit; i++) {
                String displayLine = truncateToWidth(lines[i].stripLeading(), maxWidth);
                writer.print(INDENT + icon + "   " + AnsiTheme.RESET);
                writer.println(AnsiTheme.MUTED + displayLine + AnsiTheme.RESET);
            }
            if (lines.length > 3) {
                writer.println(AnsiTheme.MUTED + INDENT + "   … +" + (lines.length - 3) + " lines" + AnsiTheme.RESET);
            }
        }
        writer.flush();
        boolean wasReasoningShown = reasoningShown;
        resetShown();
        startSpinner(wasReasoningShown);
    }

    public void toolOutputChunk(String line) {
        stopSpinnerIfActive();
        toolOutputStreaming = true;
        toolOutputLineCount++;
        if (toolOutputLineCount <= 5) {
            int maxWidth = Math.max(40, terminalWidth.getAsInt() - INDENT.length());
            String displayLine = truncateToWidth(line, maxWidth);
            writer.println(INDENT + AnsiTheme.MUTED + displayLine + AnsiTheme.RESET);
        } else {
            String counter = "\u231B Running... Read " + toolOutputLineCount + " lines";
            writer.print("\r" + INDENT + AnsiTheme.MUTED + counter + AnsiTheme.RESET);
        }
        writer.flush();
    }

    private void printResultSummary(String result) {
        if (result == null || result.isBlank()) {
            writer.println(AnsiTheme.MUTED + "Done" + AnsiTheme.RESET);
            return;
        }
        String[] lines = result.split("\n");
        int limit = Math.min(lines.length, 3);
        int maxWidth = Math.max(40, terminalWidth.getAsInt() - 6);
        for (int i = 0; i < limit; i++) {
            String displayLine = truncateToWidth(lines[i].stripLeading(), maxWidth);
            if (i > 0) writer.print(INDENT + "   ");
            writer.println(AnsiTheme.MUTED + displayLine + AnsiTheme.RESET);
        }
        if (lines.length > 3) {
            writer.println(AnsiTheme.MUTED + INDENT + "   \u2026 +" + (lines.length - 3) + " lines" + AnsiTheme.RESET);
        }
    }

    private void clearStatusLine() {
        writer.print("\r" + " ".repeat(60) + "\r");
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
        if (message != null && !hint.equals(message)) {
            writer.println(AnsiTheme.MUTED + INDENT + "  " + truncateError(message) + AnsiTheme.RESET);
        }
        writer.flush();
    }

    public void cancelled() {
        stopSpinnerIfActive();
        writer.println("\n" + INDENT + AnsiTheme.WARNING + "[Cancelled]" + AnsiTheme.RESET);
        writer.flush();
    }

    public void maxTurnsReached() {
        writer.println("\n" + INDENT + AnsiTheme.WARNING
                + "[Max turns reached] The agent has used all available turns. You can continue the conversation with a follow-up message."
                + AnsiTheme.RESET);
        writer.flush();
    }

    public void turnSummary(long elapsedMs, Long inputTokens, Long outputTokens, Long cachedTokens) {
        String tokens;
        if (inputTokens != null && outputTokens != null) {
            long total = inputTokens + outputTokens;
            if (cachedTokens != null && cachedTokens > 0) {
                tokens = String.format(" | %,d tokens (\u2191 %,d \u2193 %,d ~%,d)", total, inputTokens, outputTokens, cachedTokens);
            } else {
                tokens = String.format(" | %,d tokens (\u2191 %,d \u2193 %,d)", total, inputTokens, outputTokens);
            }
        } else {
            tokens = "";
        }
        writer.println("\n" + AnsiTheme.MUTED + INDENT + "\u2726 " + ThinkingSpinner.formatElapsed(elapsedMs) + tokens + AnsiTheme.RESET);
        writer.flush();
    }

    public void planUpdate(List<PlanUpdateEvent.TodoItem> todos) {
        if (todos == null || todos.isEmpty()) return;
        stopSpinnerIfActive();
        mdRenderer.flush();

        writer.println("\n" + AnsiTheme.CMD_NAME + "\u25CF Planning:" + AnsiTheme.RESET);
        var normalized = todos.stream().map(todoItem -> new PlanUpdateEvent.TodoItem(todoItem.content, todoItem.status.toLowerCase(Locale.ROOT))).toList();
        planRenderer.render(normalized);
        boolean wasReasoningShown = reasoningShown;
        resetShown();
        startSpinner(wasReasoningShown);
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
        startSpinner(reasoningShown);
    }

    public void startSpinner(boolean wasReasoningShown) {
        if (spinnerActive.compareAndSet(false, true)) {
            mdRenderer.flush();
            if (wasReasoningShown && !textStarted) {
                writer.println();
                writer.flush();
            }
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
