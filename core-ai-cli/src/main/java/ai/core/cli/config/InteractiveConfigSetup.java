package ai.core.cli.config;

import ai.core.cli.auth.AuthConfig;
import ai.core.cli.auth.AuthManager;
import ai.core.cli.ui.TerminalUI;

/**
 * @author stephen
 */
public class InteractiveConfigSetup {

    public static void setupIfNeeded(TerminalUI ui) {
        if (isConfigValid()) return;
        if (hasAuthCredentials()) return;

        // No provider config and not logged in — run the login flow.
        AuthManager.loginInteractive(ui, null);
    }

    static boolean isConfigValid() {
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(System.getProperty("user.home"), ".core-ai", "agent.properties"))) {
            return false;
        }
        var props = new java.util.Properties();
        try (var is = java.nio.file.Files.newInputStream(
                java.nio.file.Path.of(System.getProperty("user.home"), ".core-ai", "agent.properties"))) {
            props.load(is);
        } catch (java.io.IOException e) {
            return false;
        }
        for (String prefix : new String[]{"openrouter", "litellm", "openai", "deepseek", "azure"}) {
            String key = props.getProperty(prefix + ".api.key");
            if (key != null && !key.isBlank()) return true;
        }
        return false;
    }

    private static boolean hasAuthCredentials() {
        var auth = AuthConfig.load();
        return auth != null && auth.apiKey() != null;
    }
}
