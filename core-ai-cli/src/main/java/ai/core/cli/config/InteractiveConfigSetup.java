package ai.core.cli.config;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * @author stephen
 */
public class InteractiveConfigSetup {
    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".core-ai");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("agent.properties");

    private static final String LITELLM_API_BASE = "https://litellm.connexup-dev.net";

    public static void setupIfNeeded(TerminalUI ui) {
        if (isConfigValid()) {
            return;
        }
        runInteractiveSetup(ui);
    }

    static boolean isConfigValid() {
        if (!Files.exists(CONFIG_FILE)) {
            return false;
        }
        var props = new Properties();
        try (var is = Files.newInputStream(CONFIG_FILE)) {
            props.load(is);
        } catch (IOException e) {
            return false;
        }
        for (String prefix : new String[]{"openrouter", "litellm", "openai", "deepseek", "azure"}) {
            String key = props.getProperty(prefix + ".api.key");
            if (key != null && !key.isBlank()) return true;
        }
        return false;
    }

    private static void runInteractiveSetup(TerminalUI ui) {
        ui.printStreamingChunk("\n");
        ui.printStreamingChunk(AnsiTheme.SEPARATOR + "  Welcome to Core-AI CLI!" + AnsiTheme.RESET + "\n");
        ui.printStreamingChunk(AnsiTheme.MUTED + "  No configuration found. Let's set it up." + AnsiTheme.RESET + "\n");
        ui.printStreamingChunk("\n");
        ui.printStreamingChunk("  " + AnsiTheme.MUTED + "Provider: " + LITELLM_API_BASE + AnsiTheme.RESET + "\n");
        ui.printStreamingChunk("\n");

        String username = promptRequired(ui, "Username");
        String apiKey = promptRequired(ui, "API Key");
        String model = promptRequired(ui, "Model");
        ui.printStreamingChunk("\n");


        writeConfig(apiKey, model, username);

        ui.printStreamingChunk("\n");
        ui.printStreamingChunk(AnsiTheme.SUCCESS + "  Configuration saved to " + CONFIG_FILE + AnsiTheme.RESET + "\n");
        ui.printStreamingChunk("\n");
    }


    private static String promptRequired(TerminalUI ui, String label) {
        while (true) {
            String line = ui.readRawLine("  " + label + ": ");
            if (line != null && !line.isBlank()) {
                return line.trim();
            }
            ui.printStreamingChunk(AnsiTheme.ERROR + "  " + label + " is required." + AnsiTheme.RESET + "\n");
        }
    }

    private static void writeConfig(String apiKey, String model, String username) {
        try {
            Files.createDirectories(CONFIG_DIR);
            String content = "core.appName=core-ai-cli\n"
                    + "litellm.api.base=" + LITELLM_API_BASE + "\n"
                    + "litellm.api.key=" + apiKey + "\n"
                    + "litellm.model=" + model + "\n"
                    + "litellm.stream.buffer.size=256\n"
                    + "litellm.timeout.seconds=300\n"
                    + "litellm.models=" + model + "\n"
                    + "username=" + username + "\n"
                    + "agent.coding.enabled=false\n";
            Files.writeString(CONFIG_FILE, content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write config to " + CONFIG_FILE, e);
        }
    }
}
