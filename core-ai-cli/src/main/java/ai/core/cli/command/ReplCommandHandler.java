package ai.core.cli.command;

import ai.core.cli.DebugLog;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author stephen
 */
public class ReplCommandHandler {
    private final TerminalUI ui;

    public ReplCommandHandler(TerminalUI ui) {
        this.ui = ui;
    }

    public void handle(String input) {
        var trimmed = input.trim();
        if (!trimmed.startsWith("/")) return;

        if ("/".equals(trimmed)) {
            printHelp();
            return;
        }

        var parts = trimmed.split("\\s+", 2);
        var command = parts[0];

        switch (command) {
            case "/help" -> printHelp();
            case "/init" -> initProject();
            case "/debug" -> toggleDebug();
            case "/clear" -> clearScreen();
            case "/exit" -> {
            }
            default -> ui.printStreamingChunk(AnsiTheme.WARNING + "Unknown command: " + command + ". Type /help for available commands." + AnsiTheme.RESET + "\n");
        }
    }

    private void printHelp() {
        var sb = new StringBuilder("Available commands:\n");
        for (SlashCommand cmd : SlashCommandRegistry.all()) {
            sb.append(String.format("  %s%-16s %s%s%n",
                    AnsiTheme.CMD_NAME, cmd.name(),
                    AnsiTheme.CMD_DESC + cmd.description(), AnsiTheme.RESET));
        }
        ui.printStreamingChunk(sb.toString());
    }

    private void toggleDebug() {
        if (DebugLog.isEnabled()) {
            DebugLog.disable();
            System.clearProperty("core.ai.debug");
            ui.printStreamingChunk("Debug mode: OFF\n");
        } else {
            DebugLog.enable();
            System.setProperty("core.ai.debug", "true");
            ui.printStreamingChunk("Debug mode: ON\n");
        }
    }

    private void initProject() {
        Path coreAiMd = Path.of(".core-ai.md");
        if (Files.exists(coreAiMd)) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  .core-ai.md already exists.\n" + AnsiTheme.RESET);
            return;
        }
        String template = """
                # Project Instructions

                ## Guidelines
                - Code comments in English
                - Prefer self-descriptive code over comments

                ## Project Structure
                <!-- Describe your project structure here -->

                ## Conventions
                <!-- Add project-specific conventions -->
                """;
        try {
            Files.writeString(coreAiMd, template);
            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET
                    + " Created .core-ai.md — edit it to customize agent behavior.\n\n");
        } catch (IOException e) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Failed to create .core-ai.md: " + e.getMessage() + AnsiTheme.RESET + "\n");
        }
    }

    private void clearScreen() {
        ui.printStreamingChunk("\u001B[2J\u001B[H");
    }
}
