package ai.core.cli.remote;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * @author stephen
 */
public class RemoteCommandHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteCommandHandler.class);

    private final TerminalUI ui;

    public RemoteCommandHandler(TerminalUI ui) {
        this.ui = ui;
    }

    public RemoteConfig handle() {
        var saved = RemoteConfig.load();
        if (saved != null) {
            return handleSavedConfig(saved);
        }
        return handleNewLogin();
    }

    private RemoteConfig handleSavedConfig(RemoteConfig saved) {
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Remote server: " + AnsiTheme.RESET + saved.serverUrl() + "\n");
        var input = ui.readRawLine(AnsiTheme.MUTED + "  Saved login found. " + AnsiTheme.RESET + "Connect? (y/n/new): ");
        if (input == null) return null;
        var trimmed = input.trim().toLowerCase(java.util.Locale.ROOT);

        if ("y".equals(trimmed) || "yes".equals(trimmed) || trimmed.isEmpty()) {
            return saved;
        }
        if ("new".equals(trimmed)) {
            return handleNewLogin();
        }
        return null;
    }

    private RemoteConfig handleNewLogin() {
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Remote Server Setup" + AnsiTheme.RESET + "\n\n");

        var serverUrl = ui.readRawLine("  Server URL [http://localhost:8080]: ");
        LOGGER.debug("serverUrl input: [{}] (null={})", serverUrl, serverUrl == null);
        if (serverUrl == null) return null;
        serverUrl = serverUrl.trim();
        if (serverUrl.isEmpty()) serverUrl = "http://localhost:8080";
        if (serverUrl.endsWith("/")) serverUrl = serverUrl.substring(0, serverUrl.length() - 1);

        var email = ui.readRawLine("  Email: ");
        LOGGER.debug("email input: [{}] (null={})", email, email == null);
        if (email == null || email.isBlank()) return null;

        var password = ui.readRawLine("  Password: ");
        LOGGER.debug("password input: [{}] (null={})", password != null ? "***" : null, password == null);
        if (password == null || password.isBlank()) return null;

        ui.printStreamingChunk(AnsiTheme.MUTED + "  Logging in..." + AnsiTheme.RESET);

        var loginResult = login(serverUrl, email.trim(), password.trim());
        if (loginResult == null) return null;

        ui.printStreamingChunk("\r" + AnsiTheme.SUCCESS + "  ✓ Login successful" + AnsiTheme.RESET + "                    \n");

        var agentId = ui.readRawLine("  Agent ID (default: default-assistant): ");
        if (agentId == null || agentId.isBlank()) agentId = "default-assistant";

        var config = new RemoteConfig(serverUrl, loginResult.apiKey, agentId.trim(), loginResult.name);
        config.save();
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Config saved to ~/.core-ai/remote.json" + AnsiTheme.RESET + "\n");
        return config;
    }

    @SuppressWarnings("unchecked")
    private LoginResult login(String serverUrl, String email, String password) {
        try {
            var body = JsonUtil.toJson(Map.of("email", email, "password", password));
            var uri = URI.create(serverUrl + "/api/auth/login");
            LOGGER.debug("login request: uri={}, body={}", uri, body);
            var request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var httpClient = HttpClient.newBuilder().build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOGGER.debug("login response: status={}, body={}, uri={}",
                    response.statusCode(), response.body(), response.uri());
            if (response.statusCode() != 200) {
                ui.printStreamingChunk("\n" + AnsiTheme.ERROR + "  ✗ Login failed: " + response.body() + AnsiTheme.RESET + "\n");
                return null;
            }
            Map<String, Object> result = JsonUtil.fromJson(Map.class, response.body());
            return new LoginResult((String) result.get("api_key"), (String) result.get("name"));
        } catch (Exception e) {
            ui.printStreamingChunk("\n" + AnsiTheme.ERROR + "  ✗ Connection failed: " + e.getMessage() + AnsiTheme.RESET + "\n");
            return null;
        }
    }

    record LoginResult(String apiKey, String name) { }
}
