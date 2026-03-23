package ai.core.cli.command;

import ai.core.cli.memory.MdMemoryProvider;
import ai.core.cli.memory.MdMemoryProvider.MemoryEntry;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MemoryCommandHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryCommandHandler.class);

    private final TerminalUI ui;
    private final MdMemoryProvider memory;

    public MemoryCommandHandler(TerminalUI ui, MdMemoryProvider memory) {
        this.ui = ui;
        this.memory = memory;
    }

    public MdMemoryProvider getMemoryProvider() {
        return memory;
    }

    public void handle(String trimmed) {
        if (memory == null) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Memory not available.\n" + AnsiTheme.RESET);
            return;
        }
        String args = trimmed.length() > "/memory".length()
                ? trimmed.substring("/memory".length()).trim()
                : "";
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
            java.nio.file.Files.createDirectories(dir);
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

    private void printUsage() {
        ui.printStreamingChunk(String.format("%n  %sUsage:%s%n"
                        + "  /memory                       Show sub-command menu%n"
                        + "  /memory edit                  Select a memory file to edit%n"
                        + "  /memory search [keyword]      Search memories by keyword%n"
                        + "  /memory open                  Open memory folder in file manager%n%n",
                AnsiTheme.PROMPT, AnsiTheme.RESET));
    }
}
