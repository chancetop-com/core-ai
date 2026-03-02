package ai.core.cli.command;

import ai.core.cli.DebugLog;
import ai.core.cli.ui.TerminalUI;

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

        var parts = trimmed.split("\\s+", 2);
        var command = parts[0];

        switch (command) {
            case "/help" -> printHelp();
            case "/debug" -> toggleDebug();
            case "/clear" -> clearScreen();
            case "/exit" -> {
            }
            default -> ui.printStreamingChunk("Unknown command: " + command + ". Type /help for available commands.\n");
        }
    }

    private void printHelp() {
        ui.printStreamingChunk("""
                Available commands:
                  /help            Show this help
                  /debug           Toggle debug mode
                  /clear           Clear screen
                  /exit            Quit
                """);
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

    private void clearScreen() {
        ui.printStreamingChunk("\u001B[2J\u001B[H");
    }
}
