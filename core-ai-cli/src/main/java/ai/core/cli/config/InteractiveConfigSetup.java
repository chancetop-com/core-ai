package ai.core.cli.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * @author stephen
 */
public class InteractiveConfigSetup {
    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".core-ai-cli");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("agent.properties");

    private static final String DEFAULT_API_BASE = "https://openrouter.ai/api/v1";
    private static final String DEFAULT_MODEL = "anthropic/claude-sonnet-4.6";

    public static void setupIfNeeded() {
        if (isConfigValid()) {
            return;
        }
        runInteractiveSetup();
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
        String apiKey = props.getProperty("litellm.api.key");
        return apiKey != null && !apiKey.isBlank();
    }

    private static void runInteractiveSetup() {
        var out = System.out;
        var reader = new BufferedReader(new InputStreamReader(System.in));

        out.println();
        out.println("\u001B[36m  Welcome to Core-AI CLI!\u001B[0m");
        out.println("\u001B[90m  No configuration found. Let's set it up.\u001B[0m");
        out.println();

        String apiBase = promptWithDefault(reader, out, "API Base URL", DEFAULT_API_BASE);
        String apiKey = promptRequired(reader, out, "API Key");
        String model = promptWithDefault(reader, out, "Default Model", DEFAULT_MODEL);

        writeConfig(apiBase, apiKey, model);

        out.println();
        out.println("\u001B[32m  Configuration saved to " + CONFIG_FILE + "\u001B[0m");
        out.println();
    }

    private static String promptWithDefault(BufferedReader reader, PrintStream out, String label, String defaultValue) {
        out.print("  " + label + " [\u001B[90m" + defaultValue + "\u001B[0m]: ");
        out.flush();
        try {
            String line = reader.readLine();
            if (line == null || line.isBlank()) {
                return defaultValue;
            }
            return line.trim();
        } catch (IOException e) {
            return defaultValue;
        }
    }

    private static String promptRequired(BufferedReader reader, PrintStream out, String label) {
        while (true) {
            out.print("  " + label + ": ");
            out.flush();
            try {
                String line = reader.readLine();
                if (line != null && !line.isBlank()) {
                    return line.trim();
                }
                out.println("\u001B[31m  " + label + " is required.\u001B[0m");
            } catch (IOException e) {
                throw new RuntimeException("Failed to read input", e);
            }
        }
    }

    private static void writeConfig(String apiBase, String apiKey, String model) {
        try {
            Files.createDirectories(CONFIG_DIR);
            String content = "core.appName=core-ai-cli\n"
                    + "litellm.api.base=" + apiBase + "\n"
                    + "litellm.api.key=" + apiKey + "\n"
                    + "llm.model=" + model + "\n";
            Files.writeString(CONFIG_FILE, content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write config to " + CONFIG_FILE, e);
        }
    }
}
