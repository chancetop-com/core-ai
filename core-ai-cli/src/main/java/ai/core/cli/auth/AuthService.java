package ai.core.cli.auth;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * author cyril
 * description
 * createTime  2026/6/8
 **/
public final class AuthService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);
    private static final long CALLBACK_TIMEOUT_SECONDS = 120;

    // ── Terminal password login ─────────────────────────────────────

    /**
     * Authenticate with email/password.  On success persists the credential
     * and returns the API key.
     */
    @SuppressWarnings("unchecked")
    public static String login(String serverUrl, String email, String password) {
        try {
            var body = JsonUtil.toJson(Map.of("email", email, "password", password));
            var uri = URI.create(serverUrl + "/api/auth/login");
            var request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var httpClient = HttpClient.newBuilder().build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOGGER.warn("Login failed: {}", response.body());
                return null;
            }

            Map<String, Object> result = JsonUtil.fromJson(Map.class, response.body());
            var apiKey = (String) result.get("api_key");
            if (apiKey == null) return null;

            AuthConfig.login(serverUrl, apiKey).save();
            return apiKey;
        } catch (Exception e) {
            LOGGER.warn("Login failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Browser login ───────────────────────────────────────────────

    /**
     * Primary login entry point: shows a browser authorization link and also
     * accepts a direct API key.  Browser callback and direct key entry run
     * concurrently — whichever completes first wins.
     *
     * @param ui        terminal UI for prompts and status messages
     * @param serverUrl the core-ai-server base URL
     * @return API key string, or null if login failed or was cancelled
     */
    public static String loginOrBrowser(TerminalUI ui, String serverUrl) {
        if (browserSupported()) {
            return loginViaBrowser(ui, serverUrl);
        }
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Browser not available, using terminal login." + AnsiTheme.RESET + "\n");
        return loginViaTerminal(ui, serverUrl);
    }

    private static boolean browserSupported() {
        return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE);
    }

    private static String loginViaBrowser(TerminalUI ui, String serverUrl) {
        try (LocalCallbackServer callback = new LocalCallbackServer()) {
            var authUrl = serverUrl + "/login?callback="
                    + URLEncoder.encode("http://127.0.0.1:" + callback.port() + "/callback", StandardCharsets.UTF_8);
            ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Browser Login" + AnsiTheme.RESET + "\n\n");
            ui.printStreamingChunk(AnsiTheme.MUTED + "  " + authUrl + AnsiTheme.RESET + "\n");
            ui.printStreamingChunk(AnsiTheme.MUTED
                    + "  Open the link above in your browser, or paste an API key and press Enter."
                    + AnsiTheme.RESET + "\n");
            openBrowserIgnoreErrors(authUrl);

            final var cb = callback;
            final var mainThread = Thread.currentThread();
            final var callbackKey = new String[1];
            final var callbackReceived = new java.util.concurrent.atomic.AtomicBoolean(false);
            var callbackThread = new Thread(
                    () -> waitForCallback(cb, callbackKey, callbackReceived, mainThread), "auth-callback");
            callbackThread.setDaemon(true);
            callbackThread.start();

            var input = ui.readRawLine(
                    AnsiTheme.MUTED + "  or paste an API key and press Enter" + AnsiTheme.RESET + ": ");
            if (input != null && !input.isBlank()) {
                return tryApiKey(ui, serverUrl, input.trim(), null);
            }

            var interrupted = Thread.interrupted();
            if (callbackReceived.get()) {
                ui.printStreamingChunk(AnsiTheme.SUCCESS + "  ✓ Authorization received" + AnsiTheme.RESET + "\n");
                AuthConfig.login(serverUrl, callbackKey[0]).save();
                return callbackKey[0];
            }
            if (interrupted) {
                ui.printStreamingChunk("\n" + AnsiTheme.MUTED + "  Cancelled." + AnsiTheme.RESET + "\n");
                return null;
            }
            ui.printStreamingChunk(
                    AnsiTheme.MUTED + "  Session ended. Use /login to try again." + AnsiTheme.RESET + "\n");
            return null;
        } catch (Exception e) {
            LOGGER.warn("Browser login error: {}", e.getMessage());
            ui.printStreamingChunk(AnsiTheme.MUTED
                    + "  Browser login failed, switching to terminal login..." + AnsiTheme.RESET + "\n");
            return loginViaTerminal(ui, serverUrl);
        }
    }

    private static void openBrowserIgnoreErrors(String url) {
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            LOGGER.debug("Failed to open browser: {}", e.getMessage());
        }
    }

    private static void waitForCallback(LocalCallbackServer cb, String[] key,
                                         java.util.concurrent.atomic.AtomicBoolean received, Thread mainThread) {
        try {
            var k = cb.waitForApiKey(CALLBACK_TIMEOUT_SECONDS);
            if (k != null) {
                key[0] = k;
                received.set(true);
                mainThread.interrupt();
            }
        } catch (Exception e) {
            LOGGER.debug("Callback thread error: {}", e.getMessage());
        }
    }

    private static String loginViaTerminal(TerminalUI ui, String serverUrl) {
        var email = ui.readRawLine("  Email: ");
        if (email == null || email.isBlank()) return null;

        var password = ui.readRawLine("  Password: ");
        if (password == null || password.isBlank()) return null;

        ui.printStreamingChunk(AnsiTheme.MUTED + "  Logging in..." + AnsiTheme.RESET);
        var apiKey = login(serverUrl, email.trim(), password.trim());

        if (apiKey != null) {
            ui.printStreamingChunk("\r" + AnsiTheme.SUCCESS
                    + "  ✓ Login successful" + AnsiTheme.RESET
                    + "                    \n");
        } else {
            ui.printStreamingChunk("\n" + AnsiTheme.ERROR
                    + "  ✗ Login failed" + AnsiTheme.RESET + "\n");
        }
        return apiKey;
    }

    // ── Credential management ───────────────────────────────────────

    /**
     * Returns the saved API key for the given server URL, or null.
     */
    public static String loadApiKey(String serverUrl) {
        var config = AuthConfig.load(serverUrl);
        return config != null ? config.apiKey() : null;
    }

    /**
     * Returns true if credentials exist for the given server URL.
     */
    public static boolean isLoggedIn(String serverUrl) {
        return loadApiKey(serverUrl) != null;
    }

    /**
     * Removes saved credentials.
     */
    public static void logout() {
        AuthConfig.clear();
    }

    // ── internal ────────────────────────────────────────────────────

    /**
     * Validates an API key by calling GET /api/user/me, saves on success.
     * Falls back to terminal (email/password) login on failure.
     */
    private static String tryApiKey(TerminalUI ui, String serverUrl, String apiKey,
                                     LocalCallbackServer callback) {
        if (callback != null) closeQuietly(callback);
        AuthConfig.login(serverUrl, apiKey).save();
        var config = AuthConfig.load(serverUrl);
        if (config != null && config.apiKey() != null) {
            var userMap = fetchApiJson(serverUrl, apiKey, "/api/user/me");
            if (userMap != null) {
                ui.printStreamingChunk("\r" + AnsiTheme.SUCCESS
                        + "  ✓ API key accepted" + AnsiTheme.RESET
                        + "                    \n");
                return apiKey;
            }
        }
        AuthConfig.remove(serverUrl);
        ui.printStreamingChunk("\n" + AnsiTheme.ERROR
                + "  ✗ Invalid API key, falling back to terminal login..." + AnsiTheme.RESET + "\n");
        return loginViaTerminal(ui, serverUrl);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fetchApiJson(String serverUrl, String apiKey, String path) {
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
        } catch (Exception e) {
            LOGGER.debug("API key validation failed: {}", e.getMessage());
        }
        return null;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception e) {
            LOGGER.debug("Failed to close: {}", e.getMessage());
        }
    }

    private AuthService() { }
}
