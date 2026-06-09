package ai.core.cli.auth;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;

/**
 * author cyril
 * description
 * createTime  2026/6/8
 **/
public class AuthCommandHandler {
    private final TerminalUI ui;
    private final String defaultServerUrl;

    public AuthCommandHandler(TerminalUI ui, String defaultServerUrl) {
        this.ui = ui;
        this.defaultServerUrl = defaultServerUrl;
    }

    /**
     * Dispatches an auth command.  Returns true if the command was handled.
     */
    public boolean handle(String trimmed) {
        var lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if ("/login".equals(lower)) {
            handleLogin(null);
            return true;
        }
        if (lower.startsWith("/login ")) {
            handleLoginArgs(trimmed.substring(7).trim());
            return true;
        }
        if ("/logout".equals(lower)) {
            handleLogout();
            return true;
        }
        if ("/status".equals(lower)) {
            handleStatus();
            return true;
        }
        if (lower.startsWith("/server")) {
            handleServer(trimmed.substring(7).trim());
            return true;
        }
        return false;
    }

    // ── /login ─────────────────────────────────────────────────────────

    private void handleLogin(String serverUrl) {
        if (AuthManager.isLoggedIn()) {
            var status = AuthManager.status();
            ui.printStreamingChunk("\n" + AnsiTheme.MUTED + "  Already logged in as " + status + AnsiTheme.RESET + "\n");
            var input = ui.readRawLine("  y=Re-login, n=cancel, or enter a new server URL: ");
            if (input == null || "n".equalsIgnoreCase(input.trim())) return;
            var trimmed = input.trim();
            if (!"y".equalsIgnoreCase(trimmed) && !trimmed.isEmpty()) {
                var url = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
                AuthManager.login(ui, url);
                return;
            }
        }

        if (serverUrl != null) {
            AuthManager.login(ui, serverUrl);
        } else if (defaultServerUrl != null) {
            AuthManager.login(ui, defaultServerUrl);
        } else {
            AuthManager.loginInteractive(ui, null);
        }
    }

    private void handleLoginArgs(String args) {
        var tokenIdx = args.indexOf(" --token ");
        if (tokenIdx > 0) {
            var url = args.substring(0, tokenIdx).trim();
            var token = args.substring(tokenIdx + 9).trim();
            if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            var config = AuthManager.loginWithToken(url, token);
            if (config != null) {
                ui.printStreamingChunk(AnsiTheme.SUCCESS + "  Logged in with token @ " + url + AnsiTheme.RESET + "\n");
                ui.printStreamingChunk(AnsiTheme.MUTED + "  Remote agents are discovered at startup. "
                        + "Restart CLI to pick up the latest agents from this server." + AnsiTheme.RESET + "\n");
            } else {
                ui.printStreamingChunk(AnsiTheme.ERROR + "  Failed to validate token" + AnsiTheme.RESET + "\n");
            }
            return;
        }

        var url = args.trim();
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        handleLogin(url);
    }

    // ── /logout ────────────────────────────────────────────────────────

    private void handleLogout() {
        if (!AuthManager.isLoggedIn()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Not logged in." + AnsiTheme.RESET + "\n");
            return;
        }

        var status = AuthManager.status();
        ui.printStreamingChunk("\n  " + status + "\n");
        var input = ui.readRawLine(AnsiTheme.WARNING + "  Logout? (y/n): " + AnsiTheme.RESET);
        if (input == null || !"y".equalsIgnoreCase(input.trim())) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Cancelled." + AnsiTheme.RESET + "\n");
            return;
        }

        AuthManager.logout();
        ui.printStreamingChunk(AnsiTheme.SUCCESS + "  Logged out." + AnsiTheme.RESET + "\n");
    }

    // ── /status ────────────────────────────────────────────────────────

    private void handleStatus() {
        if (!AuthManager.isLoggedIn()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Not logged in. Use /login to authenticate." + AnsiTheme.RESET + "\n");
            return;
        }

        ui.printStreamingChunk(AnsiTheme.MUTED + "  Fetching user info..." + AnsiTheme.RESET);
        var info = AuthManager.whoami();
        if (info == null) {
            ui.printStreamingChunk("\n" + AnsiTheme.ERROR + "  Failed to fetch user info. Token may be invalid." + AnsiTheme.RESET + "\n");
            return;
        }
        ui.printStreamingChunk("\r\033[K" + info + "\n");
    }

    // ── /server ────────────────────────────────────────────────────────

    private void handleServer(String args) {
        if (args.isEmpty()) {
            showServerList();
            return;
        }

        switchToServer(args);
    }

    private void showServerList() {
        var servers = AuthManager.listServers();
        if (servers.isEmpty()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  No servers registered. Use /login first." + AnsiTheme.RESET + "\n");
            return;
        }

        var sb = new StringBuilder("\n");
        var activeUrl = AuthManager.getServerUrl();
        for (int i = 0; i < servers.size(); i++) {
            var s = servers.get(i);
            var marker = s.serverUrl().equals(activeUrl) ? "*" : " ";
            var name = s.name() != null ? s.name() : s.userId() != null ? s.userId() : "(pending)";
            sb.append(String.format("  %s [%d] %s @ %s%n", marker, i + 1, name, s.serverUrl()));
        }
        ui.printStreamingChunk(sb.toString());

        var input = ui.readRawLine("  Select [1-" + servers.size() + "] or Esc to cancel: ");
        if (input == null || input.isBlank()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Cancelled." + AnsiTheme.RESET + "\n");
            return;
        }
        switchToServer(input.trim());
    }

    private void switchToServer(String input) {
        try {
            var idx = Integer.parseInt(input) - 1;
            var servers = AuthManager.listServers();
            if (idx >= 0 && idx < servers.size()) {
                doSwitch(servers.get(idx).serverUrl());
                return;
            }
        } catch (NumberFormatException ignored) {
            // not a number, treat as URL
        }

        var targetUrl = input;
        if (targetUrl.endsWith("/")) targetUrl = targetUrl.substring(0, targetUrl.length() - 1);
        doSwitch(targetUrl);
    }

    private void doSwitch(String targetUrl) {
        var active = AuthConfig.load();
        if (active != null && active.serverUrl().equals(targetUrl)) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Already on " + targetUrl + AnsiTheme.RESET + "\n");
            return;
        }

        var config = AuthManager.switchServer(targetUrl);
        if (config == null) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  No credentials for " + targetUrl
                    + ". Use /login " + targetUrl + " first." + AnsiTheme.RESET + "\n");
            return;
        }
        ui.printStreamingChunk(AnsiTheme.SUCCESS + "  Switched to " + targetUrl + AnsiTheme.RESET + "\n");
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Remote agents are discovered at startup. "
                + "Restart CLI to pick up the latest agents from this server." + AnsiTheme.RESET + "\n");
    }
}
