package ai.core.cli.config;

import ai.core.cli.ui.AnsiTheme;
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
    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".core-ai");
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
        for (String prefix : new String[]{"openrouter", "litellm", "openai", "deepseek", "azure"}) {
            String key = props.getProperty(prefix + ".api.key");
            if (key != null && !key.isBlank()) return true;
        }
        return false;
    }

    private static void runInteractiveSetup() {
        var out = System.out;
        var reader = new BufferedReader(new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));

        out.println();
        out.println(AnsiTheme.SEPARATOR + "  Welcome to Core-AI CLI!" + AnsiTheme.RESET);
        out.println(AnsiTheme.MUTED + "  No configuration found. Let's set it up." + AnsiTheme.RESET);
        out.println();

        out.println("  " + AnsiTheme.MUTED + "Provider: OpenRouter (" + DEFAULT_API_BASE + ")" + AnsiTheme.RESET);
        out.println();
        String apiKey = promptRequired(reader, out, "API Key");
        String model = promptWithDefault(reader, out, "Default Model", DEFAULT_MODEL);

        writeConfig(apiKey, model);

        out.println();
        out.println(AnsiTheme.SUCCESS + "  Configuration saved to " + CONFIG_FILE + AnsiTheme.RESET);
        out.println();
    }

    private static String promptWithDefault(BufferedReader reader, PrintStream out, String label, String defaultValue) {
        out.print("  " + label + " [" + AnsiTheme.MUTED + defaultValue + AnsiTheme.RESET + "]: ");
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
                out.println(AnsiTheme.ERROR + "  " + label + " is required." + AnsiTheme.RESET);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read input", e);
            }
        }
    }

    private static void writeConfig(String apiKey, String model) {
        try {
            Files.createDirectories(CONFIG_DIR);
            String content = "core.appName=core-ai-cli\n"
                    + "openrouter.api.key=" + apiKey + "\n"
                    + "openrouter.model=" + model + "\n"
                    + "openrouter.models=" + model + "\n";
            Files.writeString(CONFIG_FILE, content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write config to " + CONFIG_FILE, e);
        }
    }
}
