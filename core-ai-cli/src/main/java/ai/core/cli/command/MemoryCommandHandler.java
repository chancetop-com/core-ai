package ai.core.cli.command;

import ai.core.cli.DebugLog;
import ai.core.cli.memory.LocalFileMemoryProvider;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author xander
 */
public class MemoryCommandHandler {

    private final TerminalUI ui;
    private final LocalFileMemoryProvider memory;

    public MemoryCommandHandler(TerminalUI ui, LocalFileMemoryProvider memory) {
        this.ui = ui;
        this.memory = memory;
    }

    public void handle(String trimmed) {
        if (memory == null) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Memory not available.\n" + AnsiTheme.RESET);
            return;
        }
        String args = trimmed.length() > "/memory".length()
                ? trimmed.substring("/memory".length()).trim()
                : "";
        switch (args) {
            case "read", "" -> showMemory();
            case "edit" -> openInEditor();
            case "open" -> openFolder();
            default -> printUsage();
        }
    }

    private void showMemory() {
        var content = memory.load();
        if (content.isBlank()) {
            ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "No memories saved yet." + AnsiTheme.RESET + "\n\n");
            return;
        }
        ui.printStreamingChunk("\n  " + AnsiTheme.PROMPT + "Saved Memories" + AnsiTheme.RESET + "\n\n");
        for (String line : content.split("\n")) {
            ui.printStreamingChunk("  " + line + "\n");
        }
        long globalSize = memory.sizeInBytes("global");
        long projectSize = memory.sizeInBytes("project");
        ui.printStreamingChunk(String.format("%n  %sSize: global %d bytes / 5KB, project %d bytes / 10KB%s%n%n",
                AnsiTheme.MUTED, globalSize, projectSize, AnsiTheme.RESET));
    }

    @SuppressWarnings("PMD.SystemPrintln")
    private void openInEditor() {
        Path filePath = memory.getProjectPath();
        ensureFileExists(filePath);

        String editor = System.getenv("EDITOR");
        if (editor == null) editor = System.getenv("VISUAL");
        if (editor == null) editor = "vim";

        try {
            ui.printStreamingChunk("\n  Opening " + filePath + " with " + editor + "...\n\n");
            var process = new ProcessBuilder(editor, filePath.toAbsolutePath().toString())
                    .inheritIO()
                    .start();
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            DebugLog.log("Failed to open editor: " + e.getMessage());
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Failed to open editor: " + e.getMessage() + AnsiTheme.RESET + "\n");
        }
    }

    private void openFolder() {
        Path folder = memory.getProjectPath().getParent();
        ensureFileExists(memory.getProjectPath());

        String os = System.getProperty("os.name", "").toLowerCase();
        String command = os.contains("mac") ? "open" : os.contains("win") ? "explorer" : "xdg-open";

        try {
            ui.printStreamingChunk("\n  Opening " + folder + "...\n\n");
            new ProcessBuilder(command, folder.toAbsolutePath().toString()).start();
        } catch (IOException e) {
            DebugLog.log("Failed to open folder: " + e.getMessage());
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Failed to open folder: " + e.getMessage() + AnsiTheme.RESET + "\n");
        }
    }

    private void ensureFileExists(Path filePath) {
        try {
            Files.createDirectories(filePath.getParent());
            if (!Files.exists(filePath)) {
                Files.writeString(filePath, "");
            }
        } catch (IOException e) {
            DebugLog.log("Failed to create memory file: " + e.getMessage());
        }
    }

    private void printUsage() {
        ui.printStreamingChunk(String.format("%n  %sUsage:%s%n"
                        + "  /memory              Show all memories%n"
                        + "  /memory read         Show all memories%n"
                        + "  /memory edit         Open memory file in editor ($EDITOR or vim)%n"
                        + "  /memory open         Open memory file folder%n%n",
                AnsiTheme.PROMPT, AnsiTheme.RESET));
    }
}
