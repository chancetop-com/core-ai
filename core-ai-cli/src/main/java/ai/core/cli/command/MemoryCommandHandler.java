package ai.core.cli.command;

import ai.core.cli.memory.LocalFileMemoryProvider;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.memory.MemoryProvider;

/**
 * @author xander
 */
public class MemoryCommandHandler {

    private final TerminalUI ui;
    private final MemoryProvider memory;

    public MemoryCommandHandler(TerminalUI ui, MemoryProvider memory) {
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
        if (args.isEmpty()) {
            showMemory();
        } else if (args.startsWith("add ")) {
            handleAdd(args.substring(4).trim());
        } else if (args.startsWith("forget ")) {
            forgetMemory(args.substring(7).trim());
        } else {
            printUsage();
        }
    }

    private void handleAdd(String rest) {
        boolean isGlobal = rest.startsWith("--global ");
        String text = isGlobal ? rest.substring("--global ".length()).trim() : rest;
        if (text.isBlank()) {
            ui.printStreamingChunk(AnsiTheme.WARNING + "  Nothing to add.\n" + AnsiTheme.RESET);
            return;
        }
        if (isGlobal) {
            if (memory instanceof LocalFileMemoryProvider local) {
                local.saveToScope("global", text);
                ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET
                        + " Added to global memory: " + text + "\n\n");
            } else {
                ui.printStreamingChunk(AnsiTheme.WARNING + "  --global not supported.\n" + AnsiTheme.RESET);
            }
        } else {
            addMemory(text);
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
        if (memory instanceof LocalFileMemoryProvider local) {
            long globalSize = local.sizeInBytes("global");
            long projectSize = local.sizeInBytes("project");
            ui.printStreamingChunk(String.format("%n  %sSize: global %d bytes / 5KB, project %d bytes / 10KB%s%n%n",
                    AnsiTheme.MUTED, globalSize, projectSize, AnsiTheme.RESET));
        } else {
            ui.printStreamingChunk("\n");
        }
    }

    private void addMemory(String text) {
        try {
            memory.save(text);
            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET
                    + " Added to project memory: " + text + "\n\n");
        } catch (Exception e) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Failed: " + e.getMessage() + AnsiTheme.RESET + "\n");
        }
    }

    private void forgetMemory(String keyword) {
        if (keyword.isBlank()) {
            ui.printStreamingChunk(AnsiTheme.WARNING + "  Keyword required.\n" + AnsiTheme.RESET);
            return;
        }
        int total = memory.remove(keyword);
        if (total > 0) {
            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET
                    + " Removed " + total + " matching entries.\n\n");
        } else {
            ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "No entries matched '" + keyword + "'." + AnsiTheme.RESET + "\n\n");
        }
    }

    private void printUsage() {
        ui.printStreamingChunk(String.format("%n  %sUsage:%s%n"
                        + "  /memory                       Show all memories%n"
                        + "  /memory add <text>            Add project memory%n"
                        + "  /memory add --global <text>   Add global memory%n"
                        + "  /memory forget <keyword>      Remove matching entries%n%n",
                AnsiTheme.PROMPT, AnsiTheme.RESET));
    }
}
