package ai.core.cli.agent;

import ai.core.agent.Agent;
import ai.core.cli.command.MemoryCommandHandler;
import ai.core.cli.memory.MemorySectionManager;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.cli.ui.TextUtil;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import ai.core.llm.domain.ReasoningEffort;
import ai.core.llm.domain.RoleType;
import ai.core.session.SessionManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * @author stephen
 */
class AgentSessionRunnerCommandHandler {
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final TerminalUI ui;
    private final Agent agent;
    private final String sessionId;
    private final SessionManager sessionManager;
    private final ModelPicker modelPicker;
    private final MemoryCommandHandler memoryCommand;

    AgentSessionRunnerCommandHandler(TerminalUI ui, Agent agent, String sessionId,
                                      SessionManager sessionManager, ModelPicker modelPicker,
                                      MemoryCommandHandler memoryCommand) {
        this.ui = ui;
        this.agent = agent;
        this.sessionId = sessionId;
        this.sessionManager = sessionManager;
        this.modelPicker = modelPicker;
        this.memoryCommand = memoryCommand;
    }

    void handleStats() {
        var u = agent.getCurrentTokenUsage();
        String model = modelPicker.getCurrentModelName();
        int turns = (int) agent.getMessages().stream().filter(m -> m.role == RoleType.USER).count();
        ui.printStreamingChunk(String.format("%n  %sSession Stats%s%n  Model:       %s%n  Session:     %s%n  Turns:       %d%n  Tokens:      %,d (prompt: %,d, completion: %,d)%n  Tools:       %d available%n%n",
                AnsiTheme.PROMPT, AnsiTheme.RESET, model, sessionId, turns, (long) u.getTotalTokens(), (long) u.getPromptTokens(), (long) u.getCompletionTokens(), agent.getToolCalls().size()));
    }

    void handleTools() {
        var tools = agent.getToolCalls();
        ui.printStreamingChunk(String.format("%n  %sAvailable Tools (%d)%s%n", AnsiTheme.PROMPT, tools.size(), AnsiTheme.RESET));
        for (var tool : tools) {
            var d = tool.getDescription();
            var hint = (d == null || d.isBlank()) ? "" : AnsiTheme.MUTED + " - " + d.lines().findFirst().map(l -> l.length() > 60 ? l.substring(0, 57) + "..." : l).orElse("") + AnsiTheme.RESET;
            ui.printStreamingChunk("  " + AnsiTheme.CMD_NAME + tool.getName() + AnsiTheme.RESET + hint + "\n");
        }
        ui.printStreamingChunk("\n");
    }

    @SuppressFBWarnings("SACM_STATIC_ARRAY_CREATED_IN_METHOD")
    void handleThinking(String trimmed) {
        String[] parts = trimmed.split("\\s+", 2);
        if (parts.length > 1 && !parts[1].isBlank()) {
            var arg = parts[1].trim().toLowerCase(Locale.ROOT);
            var level = AgentSessionRunnerHelper.parseLevel(arg);
            if (level == null && !"off".equals(arg) && !"none".equals(arg) && !"default".equals(arg)) {
                ui.printStreamingChunk(AnsiTheme.ERROR + "  Invalid level: " + arg + ". Use low, medium, high, max, or off.\n" + AnsiTheme.RESET);
                return;
            }
            String error = AgentSessionRunnerHelper.persistReasoningEffortToExtraBody(level);
            if (error != null) {
                ui.printStreamingChunk(AnsiTheme.WARNING + "  " + error + "\n" + AnsiTheme.RESET);
                return;
            }
            String label = level != null ? level.name().toLowerCase(Locale.ROOT) : "off (provider default)";
            ui.printStreamingChunk(AnsiTheme.SUCCESS + "  \u2713 Reasoning effort set to " + label + "\n" + AnsiTheme.RESET);
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Restart CLI for the change to take effect.\n" + AnsiTheme.RESET);
            return;
        }
        var current = AgentSessionRunnerHelper.loadReasoningEffortFromExtraBody();
        String[] levels = {"low", "medium", "high", "max", "off (provider default)"};
        var labels = new java.util.ArrayList<String>(5);
        for (String l : levels) {
            boolean isCurrent = l.startsWith("off") && current == null
                    || current != null && l.equalsIgnoreCase(current.name());
            labels.add(l + (isCurrent ? " (current)" : ""));
        }
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "Reasoning Effort" + AnsiTheme.RESET
                + AnsiTheme.MUTED + " (\u2191\u2193 select, Enter confirm, q/Esc cancel)" + AnsiTheme.RESET + "\n");
        int choice = ui.pickIndex(labels);
        if (choice < 0) return;
        var newLevel = switch (choice) {
            case 0 -> ReasoningEffort.LOW;
            case 1 -> ReasoningEffort.MEDIUM;
            case 2 -> ReasoningEffort.HIGH;
            case 3 -> ReasoningEffort.MAX;
            default -> null;
        };
        String error = AgentSessionRunnerHelper.persistReasoningEffortToExtraBody(newLevel);
        if (error != null) {
            ui.printStreamingChunk(AnsiTheme.WARNING + "  " + error + "\n" + AnsiTheme.RESET);
            return;
        }
        String label = newLevel != null ? newLevel.name().toLowerCase(Locale.ROOT) : "off (provider default)";
        ui.printStreamingChunk(AnsiTheme.SUCCESS + "  \u2713 Reasoning effort set to " + label + "\n" + AnsiTheme.RESET);
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Restart CLI for the change to take effect.\n" + AnsiTheme.RESET);
    }

    void handleExport(String trimmed) {
        String[] parts = trimmed.split("\\s+", 2);
        String filePath = parts.length > 1 ? parts[1].trim() : "session-" + sessionId + ".md";
        var sb = new StringBuilder(4096);
        sb.append("# Session: ").append(sessionId).append("\n\n");
        for (var msg : agent.getMessages()) {
            String text = msg.getTextContent();
            if (text != null) sb.append("## ").append(msg.role.name()).append("\n\n").append(text).append("\n\n");
        }
        try {
            Files.writeString(Path.of(filePath), sb.toString());
            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Exported to " + filePath + "\n\n");
        } catch (IOException e) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Export failed: " + e.getMessage() + AnsiTheme.RESET + "\n");
        }
    }

    void handleCopy() {
        var messages = agent.getMessages();
        String lastAssistant = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role == RoleType.ASSISTANT && messages.get(i).getTextContent() != null) {
                lastAssistant = messages.get(i).getTextContent();
                break;
            }
        }
        if (lastAssistant == null) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  No assistant response to copy.\n" + AnsiTheme.RESET);
            return;
        }
        try {
            var os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("clip");
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("pbcopy");
            } else {
                pb = new ProcessBuilder("sh", "-c", "if command -v wl-copy >/dev/null 2>&1; then wl-copy; elif command -v xclip >/dev/null 2>&1; then xclip -selection clipboard; else echo 'No clipboard tool found' >&2; exit 1; fi");
            }
            var process = pb.start();
            writeToProcess(process, lastAssistant);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                ui.printStreamingChunk(AnsiTheme.ERROR + "  Failed to copy: clipboard command exited with " + exitCode + AnsiTheme.RESET + "\n");
                return;
            }
            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "\u2713" + AnsiTheme.RESET + " Copied to clipboard (" + lastAssistant.length() + " chars)\n\n");
        } catch (IOException | InterruptedException e) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Failed to copy: " + e.getMessage() + AnsiTheme.RESET + "\n");
        }
    }

    private void writeToProcess(Process process, String content) throws IOException {
        try (var out = process.getOutputStream()) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    void handleCompact() {
        var messages = agent.getMessages();
        var compression = agent.getCompression();
        if (messages.size() <= 4 || compression == null) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Nothing to compact.\n" + AnsiTheme.RESET);
            return;
        }
        int beforeCount = messages.size();
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Compacting...\n" + AnsiTheme.RESET);
        var compressed = compression.forceCompress(messages);
        if (compressed.equals(messages)) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Nothing to compact.\n" + AnsiTheme.RESET);
            return;
        }
        messages.clear();
        messages.addAll(compressed);
        if (agent.hasPersistenceProvider()) agent.save(sessionId);
        if (memoryCommand != null) {
            MemorySectionManager.reloadAgentMemorySection(agent, memoryCommand.getMemoryProvider());
        }
        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Compacted: "
                + beforeCount + " → " + messages.size() + " messages\n\n");
    }

    void handleUndo() {
        var messages = agent.getMessages();
        int idx = messages.size() - 1;
        while (idx >= 0 && messages.get(idx).role != RoleType.USER) idx--;
        if (idx < 0) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Nothing to undo.\n" + AnsiTheme.RESET);
            return;
        }
        String preview = messages.get(idx).getTextContent();
        if (preview != null && preview.length() > 60) preview = preview.substring(0, 57) + "...";
        int removed = messages.size() - idx;
        messages.subList(idx, messages.size()).clear();
        if (agent.hasPersistenceProvider()) agent.save(sessionId);
        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Undone " + removed
                + " message(s): " + AnsiTheme.MUTED + preview + AnsiTheme.RESET + "\n\n");
    }

    String showSessionPicker() {
        var sessions = sessionManager.listSessions();
        if (sessions.isEmpty()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "No saved sessions found." + AnsiTheme.RESET + "\n");
            return null;
        }
        int pageStart = 0;
        while (true) {
            int pageEnd = Math.min(pageStart + 8, sessions.size());
            List<String> labels = new java.util.ArrayList<>(10);
            for (int i = pageStart; i < pageEnd; i++) {
                var s = sessions.get(i);
                String marker = s.id().equals(sessionId) ? " (current)" : "";
                String timeStr = LocalDateTime.ofInstant(s.lastModified(), ZoneId.systemDefault()).format(DISPLAY_FORMAT);
                String title = sessionManager.firstUserMessage(s.id());
                String display = title != null && !title.isBlank() ? TextUtil.truncateByDisplayWidth(title.replaceAll("[\\r\\n]+", " "), 50) : s.id();
                labels.add(display + " (" + timeStr + ")" + marker);
            }
            if (pageStart > 0) labels.add("← Previous sessions");
            if (pageEnd < sessions.size()) labels.add("More sessions →");
            ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "Recent sessions " + (pageStart + 1) + "-" + pageEnd + " of " + sessions.size() + ":" + AnsiTheme.RESET + AnsiTheme.MUTED + " (↑↓ select, Enter confirm, q/Esc cancel)" + AnsiTheme.RESET + "\n");
            int choice = ui.pickIndex(labels);
            if (choice < 0) return null;
            if (choice < pageEnd - pageStart) {
                var picked = sessions.get(pageStart + choice).id();
                if (picked.equals(sessionId)) {
                    ui.printStreamingChunk(AnsiTheme.MUTED + "Already in this session." + AnsiTheme.RESET + "\n");
                    return null;
                }
                ui.printStreamingChunk(AnsiTheme.MUTED + "Switching to session: " + picked + AnsiTheme.RESET + "\n");
                return picked;
            }
            if (pageStart > 0 && choice == pageEnd - pageStart) {
                pageStart = Math.max(0, pageStart - 8);
            } else {
                pageStart = pageEnd;
            }
        }
    }

    Agent getAgent() {
        return agent;
    }
}
