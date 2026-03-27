package ai.core.cli.config;

import ai.core.cli.ui.AnsiTheme;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * @author stephen
 */
public class InteractiveConfigSetup {
    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".core-ai");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("agent.properties");

    private static final String LITELLM_API_BASE = "https://litellm.connexup-dev.net";
    private static final String LITELLM_MASTER_KEY = "admin";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        out.println();
        out.println(AnsiTheme.SEPARATOR + "  Welcome to Core-AI CLI!" + AnsiTheme.RESET);
        out.println(AnsiTheme.MUTED + "  No configuration found. Let's set it up." + AnsiTheme.RESET);
        out.println();
        out.println("  " + AnsiTheme.MUTED + "Provider: " + LITELLM_API_BASE + AnsiTheme.RESET);
        out.println();

        String username = promptRequired(reader, out, "Username");

        out.println();
        out.print(AnsiTheme.MUTED + "  Generating API key..." + AnsiTheme.RESET);
        out.flush();

        String apiKey;
        try {
            apiKey = generateApiKey(username);
        } catch (Exception e) {
            out.println();
            out.println(AnsiTheme.ERROR + "  Failed to generate API key: " + e.getMessage() + AnsiTheme.RESET);
            throw new RuntimeException("Failed to generate API key", e);
        }
        out.println(" " + AnsiTheme.SUCCESS + "done" + AnsiTheme.RESET);
        out.println();

        List<String> models;
        try {
            models = fetchModels(apiKey);
        } catch (Exception e) {
            out.println(AnsiTheme.WARNING + "  Failed to fetch model list, using defaults." + AnsiTheme.RESET);
            out.println();
            models = List.of(
                    "openrouter/minimax/minimax-m2.5",
                    "openrouter/minimax/minimax-m2.7"
            );
        }

        String model = selectModel(reader, out, models);
        writeConfig(apiKey, model, username);

        out.println();
        out.println(AnsiTheme.SUCCESS + "  Configuration saved to " + CONFIG_FILE + AnsiTheme.RESET);
        out.println();
    }

    private static String generateApiKey(String username) throws Exception {
        var client = HttpClient.newHttpClient();
        String body = OBJECT_MAPPER.writeValueAsString(Map.of("user_id", username, "key_alias", username, "models", List.of("openrouter/minimax/minimax-m2.5", "openrouter/minimax/minimax-m2.7", "openrouter/google/gemini-3.1-flash-lite-preview", "openrouter/moonshotai/kimi-k2")));
        var request = HttpRequest.newBuilder()
                .uri(URI.create(LITELLM_API_BASE + "/key/generate"))
                .header("Content-Type", "application/json")
                .header("x-litellm-api-key", LITELLM_MASTER_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
        JsonNode json = OBJECT_MAPPER.readTree(response.body());
        JsonNode keyNode = json.get("key");
        if (keyNode == null || keyNode.isNull()) {
            throw new RuntimeException("No 'key' field in response: " + response.body());
        }
        return keyNode.asText();
    }

    private static List<String> fetchModels(String apiKey) throws Exception {
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
                .uri(URI.create(LITELLM_API_BASE + "/models"))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode json = OBJECT_MAPPER.readTree(response.body());
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
        return models.isEmpty() ? List.of("openrouter/minimax/minimax-m2.7") : models;
    }

    private static String selectModel(BufferedReader reader, PrintStream out, List<String> models) {
        out.println("  Available models:");
        out.println();
        for (int i = 0; i < models.size(); i++) {
            out.printf("  %s%d)%s %s%n", AnsiTheme.PROMPT, i + 1, AnsiTheme.RESET, models.get(i));
        }
        out.println();
        while (true) {
            out.print("  Select model (1-" + models.size() + "): ");
            out.flush();
            try {
                String line = reader.readLine();
                if (line == null) return models.getFirst();
                try {
                    int idx = Integer.parseInt(line.trim());
                    if (idx >= 1 && idx <= models.size()) return models.get(idx - 1);
                } catch (NumberFormatException ignored) {
                    // invalid input
                }
                out.println(AnsiTheme.ERROR + "  Invalid selection, please enter a number between 1 and " + models.size() + "." + AnsiTheme.RESET);
            } catch (IOException e) {
                return models.getFirst();
            }
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

    private static void writeConfig(String apiKey, String model, String userName) {
        try {
            Files.createDirectories(CONFIG_DIR);
            String content = "core.appName=core-ai-cli\n"
                    + "litellm.api.base=" + LITELLM_API_BASE + "\n"
                    + "litellm.api.key=" + apiKey + "\n"
                    + "litellm.model=" + model + "\n"
                    + "litellm.stream.buffer.size=256\n"
                    + "litellm.timeout.seconds=300\n"
                    + "litellm.models=" + model + "\n"
                    + "username=" + userName + "\n";
            Files.writeString(CONFIG_FILE, content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write config to " + CONFIG_FILE, e);
        }
    }
}
