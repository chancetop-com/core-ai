package ai.core.cli.auth;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.cli.utils.PathUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Properties;

/**
 * Standalone login entry for {@code core-ai-cli --login [server-url]}, usable outside
 * the interactive REPL. Saves credentials to {@code ~/.core-ai/auth.json} (via
 * {@link AuthManager}) so subsequent commands such as {@code core-ai-cli mcp ...} are
 * authenticated. Exit code 0 = logged in, 1 = failed or cancelled.
 *
 * @author stephen
 */
public final class LoginCommand {
    public static int run(String serverUrlArg) {
        var ui = new TerminalUI();
        try {
            AuthConfig config;
            if (serverUrlArg != null && !serverUrlArg.isBlank()) {
                config = AuthManager.login(ui, normalize(serverUrlArg));
            } else {
                config = AuthManager.loginInteractive(ui, configuredDefaultServerUrl());
            }
            if (config == null) {
                ui.printStreamingChunk("\n" + AnsiTheme.ERROR + "  Login failed or cancelled." + AnsiTheme.RESET + "\n");
                return 1;
            }
            printSuccess(ui, config.serverUrl());
            return 0;
        } finally {
            closeQuietly(ui);
        }
    }

    private static void printSuccess(TerminalUI ui, String serverUrl) {
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Authenticated as active server: " + serverUrl + AnsiTheme.RESET + "\n");
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Server tools: core-ai-cli mcp search \"<what you need>\""
                + AnsiTheme.RESET + "\n");
    }

    /**
     * Default URL shown in the interactive prompt: the {@code core.server.url} of
     * {@code ~/.core-ai/agent.properties} if configured, otherwise null (which makes
     * {@link AuthManager} fall back to its built-in default).
     */
    private static String configuredDefaultServerUrl() {
        var configFile = PathUtils.DEFAULT_CONFIG;
        if (!Files.exists(configFile)) return null;
        try (InputStream in = Files.newInputStream(configFile)) {
            var props = new Properties();
            props.load(in);
            return props.getProperty("core.server.url");
        } catch (IOException e) {
            return null;
        }
    }

    private static String normalize(String serverUrl) {
        var trimmed = serverUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static void closeQuietly(TerminalUI ui) {
        try {
            ui.close();
        } catch (IOException ignored) {
            // terminal cleanup failure is non-critical
        }
    }

    private LoginCommand() {
    }
}
