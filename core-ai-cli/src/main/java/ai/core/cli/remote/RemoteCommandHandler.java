package ai.core.cli.remote;

import ai.core.cli.DebugLog;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.utils.JsonUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * @author stephen
 */
public class RemoteCommandHandler {
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
        var trimmed = input.trim().toLowerCase();

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
        DebugLog.log("serverUrl input: [" + serverUrl + "] (null=" + (serverUrl == null) + ")");
        if (serverUrl == null) return null;
        serverUrl = serverUrl.trim();
        if (serverUrl.isEmpty()) serverUrl = "http://localhost:8080";
        if (serverUrl.endsWith("/")) serverUrl = serverUrl.substring(0, serverUrl.length() - 1);

        var email = ui.readRawLine("  Email: ");
        DebugLog.log("email input: [" + email + "] (null=" + (email == null) + ")");
        if (email == null || email.isBlank()) return null;

        var password = ui.readRawLine("  Password: ");
        DebugLog.log("password input: [" + password + "] (null=" + (password == null) + ")");
        if (password == null || password.isBlank()) return null;

        ui.printStreamingChunk(AnsiTheme.MUTED + "  Logging in..." + AnsiTheme.RESET);

        var apiKey = login(serverUrl, email.trim(), password.trim());
        if (apiKey == null) return null;

        ui.printStreamingChunk("\r" + AnsiTheme.SUCCESS + "  ✓ Login successful" + AnsiTheme.RESET + "                    \n");

        var agentId = ui.readRawLine("  Agent ID (default: default-assistant): ");
        if (agentId == null || agentId.isBlank()) agentId = "default-assistant";

        var config = new RemoteConfig(serverUrl, apiKey, agentId.trim());
        config.save();
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Config saved to ~/.core-ai/remote.json" + AnsiTheme.RESET + "\n");
        return config;
    }

    @SuppressWarnings("unchecked")
    private String login(String serverUrl, String email, String password) {
        try {
            var body = JsonUtil.toJson(Map.of("email", email, "password", password));
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl + "/api/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var httpClient = HttpClient.newBuilder().build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                ui.printStreamingChunk("\n" + AnsiTheme.ERROR + "  ✗ Login failed: " + response.body() + AnsiTheme.RESET + "\n");
                return null;
            }
            Map<String, Object> result = JsonUtil.fromJson(Map.class, response.body());
            return (String) result.get("api_key");
        } catch (Exception e) {
            ui.printStreamingChunk("\n" + AnsiTheme.ERROR + "  ✗ Connection failed: " + e.getMessage() + AnsiTheme.RESET + "\n");
            return null;
        }
    }
}
