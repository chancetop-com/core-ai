package ai.core.cli.remote;

import ai.core.cli.auth.AuthConfig;
import ai.core.cli.auth.AuthManager;
import ai.core.cli.auth.AuthService;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the {@code /remote} slash command: connects to a remote
 * core-ai-server.  Authentication is delegated to {@link AuthService},
 * connection config is stored in {@link RemoteConfig}.
 *
 * @author stephen
 */
public class RemoteCommandHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteCommandHandler.class);

    private final TerminalUI ui;
    private final String defaultServerUrl;

    public RemoteCommandHandler(TerminalUI ui, String defaultServerUrl) {
        this.ui = ui;
        this.defaultServerUrl = defaultServerUrl;
    }

    public RemoteConfig handle() {
        // 1. Saved remote.json exists and still authenticated
        var saved = RemoteConfig.load();
        if (saved != null && AuthService.isLoggedIn(saved.serverUrl())) {
            return handleSavedConfig(saved);
        }

        // 2. Already logged in (via /login or /server) — use active config, just pick agent
        if (AuthManager.isLoggedIn()) {
            return handleActiveLogin();
        }

        // 3. Not logged in at all — full login flow
        return handleNewLogin();
    }

    private RemoteConfig handleActiveLogin() {
        var active = AuthConfig.load();
        var serverUrl = active.serverUrl();
        var name = active.name() != null ? active.name() : (active.userId() != null ? active.userId() : "authenticated");

        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Remote server: " + AnsiTheme.RESET + serverUrl + "\n");
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Using active login as " + name + AnsiTheme.RESET + "\n");

        var agentId = ui.readRawLine("  Agent ID (default: default-assistant): ");
        if (agentId == null) return null;
        if (agentId.isBlank()) agentId = "default-assistant";

        var config = new RemoteConfig(serverUrl, agentId.trim(), null);
        config.save();
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Saved to ~/.core-ai/remote.json" + AnsiTheme.RESET + "\n");
        return config;
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

        var promptDefault = defaultServerUrl != null ? defaultServerUrl : "https://core-ai-server.connexup-uat.net";
        var serverUrl = ui.readRawLine("  Server URL [" + promptDefault + "]: ");
        LOGGER.debug("serverUrl input: [{}] (null={})", serverUrl, serverUrl == null);
        if (serverUrl == null) return null;
        serverUrl = serverUrl.trim();
        if (serverUrl.isEmpty()) serverUrl = promptDefault;
        if (serverUrl.endsWith("/")) serverUrl = serverUrl.substring(0, serverUrl.length() - 1);

        var apiKey = AuthService.loginOrBrowser(ui, serverUrl);
        if (apiKey == null) return null;

        var agentId = ui.readRawLine("  Agent ID (default: default-assistant): ");
        if (agentId == null || agentId.isBlank()) agentId = "default-assistant";

        var config = new RemoteConfig(serverUrl, agentId.trim(), null);
        config.save();
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Config saved to ~/.core-ai/remote.json" + AnsiTheme.RESET + "\n");
        return config;
    }
}
