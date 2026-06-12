package ai.core.cli.auth;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * author cyril
 * description
 * createTime  2026/6/8
 **/
public final class AuthManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthManager.class);

    // ── Login ──────────────────────────────────────────────────────────

    /**
     * Interactive login: prompts for server URL, then delegates to
     * {@link AuthService#loginOrBrowser}.  On success enriches the saved
     * config with user profile info.
     *
     * @param defaultUrl default server URL to show in the prompt,
     *        or null to use the built-in default
     */
    public static AuthConfig loginInteractive(TerminalUI ui, String defaultUrl) {
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Login" + AnsiTheme.RESET + "\n\n");

        var promptDefault = defaultUrl != null ? defaultUrl : "https://core-ai-server.connexup-uat.net";
        var serverUrl = ui.readRawLine("  Server URL [" + promptDefault + "]: ");
        if (serverUrl == null) return null;
        serverUrl = serverUrl.trim();
        if (serverUrl.isEmpty()) serverUrl = promptDefault;
        if (serverUrl.endsWith("/")) serverUrl = serverUrl.substring(0, serverUrl.length() - 1);

        return login(ui, serverUrl);
    }

    /**
     * Login to a specific server URL (no interactive URL prompt).
     */
    public static AuthConfig login(TerminalUI ui, String serverUrl) {
        var apiKey = AuthService.loginOrBrowser(ui, serverUrl);
        if (apiKey == null) return null;

        var config = enrich(serverUrl, apiKey, ui);
        if (config != null) {
            RuntimeAuthConfig.instance().update(serverUrl + "/api/litellm/v1", apiKey);
        }
        return config;
    }

    /**
     * Login with an existing API key (no email/password prompt).
     * Validates the key by calling {@code GET /api/user/me}.
     */
    public static AuthConfig loginWithToken(String serverUrl, String apiKey) {
        AuthConfig.login(serverUrl, apiKey).save();
        var config = enrich(serverUrl, apiKey, null);
        if (config != null) {
            RuntimeAuthConfig.instance().update(serverUrl + "/api/litellm/v1", apiKey);
        }
        return config;
    }

    // ── Logout ─────────────────────────────────────────────────────────

    /** Logs out of the currently active server only. */
    public static void logout() {
        var active = AuthConfig.load();
        if (active != null) AuthConfig.remove(active.serverUrl());
        RuntimeAuthConfig.instance().clear();
    }

    // ── Server management ──────────────────────────────────────────────

    /** Returns all registered server configs. */
    public static List<AuthConfig> listServers() {
        return AuthConfig.loadAll();
    }

    /**
     * Switches to a previously authenticated server.
     * Returns the activated config, or null if the server is not registered.
     */
    public static AuthConfig switchServer(String serverUrl) {
        var config = AuthConfig.activate(serverUrl);
        if (config != null) {
            RuntimeAuthConfig.instance().update(serverUrl + "/api/litellm/v1", config.apiKey());
        }
        return config;
    }

    // ── Queries ────────────────────────────────────────────────────────

    /**
     * Returns a human-readable user info string, or null if not logged in.
     */
    public static String whoami() {
        var config = AuthConfig.load();
        if (config == null || config.apiKey() == null) return null;

        if (config.userId() != null) {
            return formatWhoami(config);
        }

        var enriched = enrich(config.serverUrl(), config.apiKey(), null);
        if (enriched == null || enriched.userId() == null) return null;
        return formatWhoami(enriched);
    }

    /**
     * Returns a one-line auth status summary, or null if not logged in.
     */
    public static String status() {
        var config = AuthConfig.load();
        if (config == null || config.apiKey() == null) return null;

        var name = config.name() != null ? config.name() : config.userId();
        if (name == null) name = "(authenticated)";
        var role = config.role() != null ? " (" + config.role() + ")" : "";
        return name + role + " @ " + config.serverUrl();
    }

    public static boolean isLoggedIn() {
        var config = AuthConfig.load();
        return config != null && config.apiKey() != null;
    }

    public static String getServerUrl() {
        var config = AuthConfig.load();
        return config != null ? config.serverUrl() : null;
    }

    // ── Private helpers ────────────────────────────────────────────────

    private static AuthConfig enrich(String serverUrl, String apiKey, TerminalUI ui) {
        try {
            var userMap = fetchJson(serverUrl, apiKey, "/api/user/me");
            if (userMap == null) return AuthConfig.load(serverUrl);

            var userId = (String) userMap.get("id");
            var name = (String) userMap.get("name");
            String role = null;
            // role is not in UserView; it comes from the login response.
            // Re-fetch from an existing config or just leave it null.
            var existing = AuthConfig.load(serverUrl);
            if (existing != null && existing.role() != null) role = existing.role();

            var full = AuthConfig.full(serverUrl, apiKey, userId, name, role);
            full.save();

            if (ui != null) {
                var displayName = name != null ? name : userId;
                ui.printStreamingChunk(AnsiTheme.SUCCESS + "  Logged in as " + displayName
                        + " @ " + serverUrl + AnsiTheme.RESET + "\n");
                ui.printStreamingChunk(AnsiTheme.MUTED + "  Remote agents are discovered at startup. "
                        + "Restart CLI to pick up the latest agents from this server." + AnsiTheme.RESET + "\n");
            }
            return full;
        } catch (Exception e) {
            LOGGER.warn("Failed to fetch user profile: {}", e.getMessage());
            return AuthConfig.load(serverUrl);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fetchJson(String serverUrl, String apiKey, String path) {
        try {
            var uri = URI.create(serverUrl + path);
            var request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
            var httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return JsonUtil.fromJson(Map.class, response.body());
            }
            LOGGER.warn("API {} returned status {}: {}", path, response.statusCode(), response.body());
            return null;
        } catch (Exception e) {
            LOGGER.warn("API {} request failed: {} ({})", path, e.getClass().getSimpleName(), e.getMessage(), e);
            return null;
        }
    }

    private static String formatWhoami(AuthConfig config) {
        var sb = new StringBuilder();
        sb.append("  User:     ").append(config.name() != null ? config.name() : config.userId()).append("\n");
        if (config.userId() != null) sb.append("  Email:    ").append(config.userId()).append("\n");
        if (config.role() != null) sb.append("  Role:     ").append(config.role()).append("\n");
        sb.append("  Server:   ").append(config.serverUrl()).append("\n");
        if (config.loginAt() != null) sb.append("  Login at: ").append(config.loginAt());
        return sb.toString();
    }

    private AuthManager() { }
}
