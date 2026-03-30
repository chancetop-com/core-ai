package ai.core.cli.config;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    private static List<String> fetchModels(String apiKey) throws Exception {
        var client = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder()
                .uri(URI.create(LITELLM_API_BASE + "/models"))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        var response = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode json = JsonUtil.OBJECT_MAPPER.readTree(response.body());
        JsonNode data = json.get("data");
        List<String> models = new ArrayList<>();
        if (data != null && data.isArray()) {
            for (JsonNode item : data) {
                JsonNode id = item.get("id");
                if (id != null && !id.isNull()) {
                    models.add(id.asText());
                }
            }
        }
        models.sort(String::compareTo);
        return models.isEmpty() ? List.of("openrouter/minimax/minimax-m2.7") : models;
    }

    private static String selectModel(TerminalUI ui, List<String> models) {
        ui.printStreamingChunk("  Available models:\n\n");
        for (int i = 0; i < models.size(); i++) {
            ui.printStreamingChunk(String.format("  %s%d)%s %s%n", AnsiTheme.PROMPT, i + 1, AnsiTheme.RESET, models.get(i)));
        }
        ui.printStreamingChunk("\n");
        while (true) {
            String line = ui.readRawLine("  Select model (1-" + models.size() + "): ");
            if (line == null) return models.getFirst();
            try {
                int idx = Integer.parseInt(line.trim());
                if (idx >= 1 && idx <= models.size()) return models.get(idx - 1);
            } catch (NumberFormatException ignored) {
                // invalid input
            }
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Invalid selection, please enter a number between 1 and " + models.size() + "." + AnsiTheme.RESET + "\n");
        }
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
                    + "username=" + username + "\n";
            Files.writeString(CONFIG_FILE, content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write config to " + CONFIG_FILE, e);
        }
    }
}
