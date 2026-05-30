package ai.core.cli.command;

import ai.core.cli.memory.MdMemoryProvider;
import ai.core.cli.memory.MdMemoryProvider.MemoryEntry;
import ai.core.cli.memory.MemoryTriggerService;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class MemoryCommandHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryCommandHandler.class);

    private final TerminalUI ui;
    private final MdMemoryProvider memory;
    private final MemoryTriggerService triggerService;

    public MemoryCommandHandler(TerminalUI ui, MdMemoryProvider memory,
                                MemoryTriggerService triggerService) {
        this.ui = ui;
        this.memory = memory;
        this.triggerService = triggerService;
    }

    public MdMemoryProvider getMemoryProvider() {
        return memory;
    }

    public void handle(String trimmed) {
        String args = trimmed.length() > "/memory".length()
                ? trimmed.substring("/memory".length()).trim()
                : "";
        if ("enable".equals(args)) {
            setMemoryEnabled(true);
            return;
        } else if ("disable".equals(args)) {
            setMemoryEnabled(false);
            return;
        }
        if (memory == null) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Memory not available.\n" + AnsiTheme.RESET);
            return;
        }
        if (args.isEmpty()) {
            printUsage();
        } else if ("search".equals(args)) {
            promptSearch();
        } else if (args.startsWith("search ")) {
            searchAndPick(args.substring(7).trim());
        } else if ("edit".equals(args)) {
            showAndPick(memory.listMemories());
        } else if ("open".equals(args)) {
            openFolder();
        } else if ("clear".equals(args)) {
            clearMemory();
        } else {
            printUsage();
        }
    }

    private void promptSearch() {
        String query = ui.readRawLine("  Search keyword: ");
        if (query != null && !query.isBlank()) {
            searchAndPick(query.trim());
        }
    }

    private void showAndPick(List<MemoryEntry> entries) {
        if (entries.isEmpty()) {
            ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "No memories found." + AnsiTheme.RESET + "\n\n");
            return;
        }
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Select memory to edit:" + AnsiTheme.RESET + "\n");
        List<String> labels = new ArrayList<>();
        for (MemoryEntry entry : entries) {
            labels.add(entry.toSummary());
        }
        int idx = ui.pickIndex(labels);
        if (idx < 0) return;
        openInEditor(entries.get(idx).absolutePath());
    }

    private void searchAndPick(String query) {
        if (query.isBlank()) {
            ui.printStreamingChunk(AnsiTheme.WARNING + "  Search query required.\n" + AnsiTheme.RESET);
            return;
        }
        List<MemoryEntry> results = memory.searchMemories(query);
        if (results.isEmpty()) {
            ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "No entries matched '" + query + "'."
                    + AnsiTheme.RESET + "\n\n");
            return;
        }
        showAndPick(results);
    }

    private void openInEditor(String filePath) {
        String editor = System.getenv("EDITOR");
        if (editor == null || editor.isBlank()) {
            editor = "vim";
        }
        ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "Opening " + filePath + " ..." + AnsiTheme.RESET + "\n");
        try {
            new ProcessBuilder(editor, filePath)
                    .inheritIO()
                    .start()
                    .waitFor();
            ui.printStreamingChunk("  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Editor closed.\n\n");
        } catch (IOException e) {
            LOGGER.warn("Failed to open editor: {}", e.getMessage());
            ui.printStreamingChunk("  " + AnsiTheme.ERROR + "Failed to open editor: " + e.getMessage()
                    + AnsiTheme.RESET + "\n\n");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ui.printStreamingChunk("  " + AnsiTheme.WARNING + "Editor interrupted." + AnsiTheme.RESET + "\n\n");
        }
    }

    private void openFolder() {
        var dir = memory.getMemoryDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.warn("Failed to create memory dir: {}", e.getMessage());
        }
        String dirPath = dir.toAbsolutePath().toString();
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String cmd = os.contains("mac") ? "open" : os.contains("win") ? "explorer" : "xdg-open";
        ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "Opening " + dirPath + " ..." + AnsiTheme.RESET + "\n\n");
        try {
            new ProcessBuilder(cmd, dirPath).start();
        } catch (IOException e) {
            LOGGER.warn("Failed to open folder: {}", e.getMessage());
            ui.printStreamingChunk("  " + AnsiTheme.ERROR + "Failed: " + e.getMessage()
                    + AnsiTheme.RESET + "\n\n");
        }
    }

    private void clearMemory() {
        ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "This will delete all knowledge wiki pages and MEMORY.md."
                + "\n  daily-logs, episodes, and log.md are preserved."
                + "\n  Continue? (y/N) " + AnsiTheme.RESET);
        String confirm = ui.readRawLine("");
        if (!"y".equalsIgnoreCase(confirm != null ? confirm.trim() : "")) {
            ui.printStreamingChunk("  Cancelled.\n\n");
            return;
        }
        try {
            triggerService.clearKnowledge();
            ui.printStreamingChunk("  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET
                    + " Knowledge cleared and directory structure recreated.\n\n");
        } catch (Exception e) {
            LOGGER.warn("Failed to clear knowledge: {}", e.getMessage());
            ui.printStreamingChunk("  " + AnsiTheme.ERROR + "Failed: " + e.getMessage()
                    + AnsiTheme.RESET + "\n\n");
        }
    }

    private boolean readMemoryEnabled() {
        try {
            Path configFile = ai.core.cli.utils.PathUtils.DEFAULT_CONFIG;
            if (!Files.exists(configFile)) return false;
            List<String> lines = Files.readAllLines(configFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.trim().startsWith("agent.memory.enabled=")) {
                    return line.replaceFirst("(?i)agent\\.memory\\.enabled\\s*=\\s*", "").trim().equalsIgnoreCase("true");
                }
            }
        } catch (IOException e) {
            LOGGER.debug("Failed to read memory config: {}", e.getMessage());
        }
        return false;
    }

    private void setMemoryEnabled(boolean enabled) {
        try {
            Path configFile = ai.core.cli.utils.PathUtils.DEFAULT_CONFIG;
            if (!Files.exists(configFile)) {
                ui.printStreamingChunk("  " + AnsiTheme.ERROR + "Config file not found: " + configFile + "\n" + AnsiTheme.RESET);
                return;
            }
            String replacement = "agent.memory.enabled=" + enabled;
            List<String> lines = Files.readAllLines(configFile, StandardCharsets.UTF_8);
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().startsWith("agent.memory.enabled=")) {
                    lines.set(i, lines.get(i).replaceFirst("agent\\.memory\\.enabled\\s*=\\s*\\S+", replacement));
                    found = true;
                    break;
                }
            }
            if (!found) {
                lines.add(replacement);
            }
            Files.write(configFile, lines, StandardCharsets.UTF_8);
            String status = enabled ? "enabled" : "disabled";
            ui.printStreamingChunk("  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET
                    + " Memory " + status + ". Restart the CLI for the change to take effect.\n\n");
        } catch (IOException e) {
            LOGGER.warn("Failed to update memory config: {}", e.getMessage());
            ui.printStreamingChunk("  " + AnsiTheme.ERROR + "Failed: " + e.getMessage() + "\n" + AnsiTheme.RESET);
        }
    }

    private void printUsage() {
        boolean enabled = readMemoryEnabled();
        String statusIcon = enabled ? AnsiTheme.SUCCESS + "●" + AnsiTheme.RESET : AnsiTheme.ERROR + "○" + AnsiTheme.RESET;
        String statusText = enabled ? "enabled" : "disabled";
        ui.printStreamingChunk(String.format("%n  %sUsage: %s Memory is %s%s%n"
                        + "  /memory                       Show sub-command menu%n"
                        + "  /memory enable                Enable memory (set agent.memory.enabled=true)%n"
                        + "  /memory disable               Disable memory (set agent.memory.enabled=false)%n"
                        + "  /memory edit                  Select a memory file to edit%n"
                        + "  /memory search [keyword]      Search memories by keyword%n"
                        + "  /memory open                  Open memory folder in file manager%n"
                        + "  /memory clear                 Delete knowledge wiki pages, recreate structure%n%n",
                AnsiTheme.PROMPT, AnsiTheme.RESET, statusIcon, statusText));
    }
}
