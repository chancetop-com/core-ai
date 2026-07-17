package ai.core.server;

import ai.core.server.github.GitHubInstallationTokenService;
import core.framework.module.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class GitHubModule extends Module {
    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubModule.class);

    @Override
    protected void initialize() {
        var appId = property("github.app.id").orElse(null);
        var privateKey = property("github.app.private_key").orElse(null);
        var installationId = property("github.app.installation_id")
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(Long::parseLong)
                .orElse(null);
        if (appId != null && installationId != null && privateKey != null && !appId.isBlank() && !privateKey.isBlank() && privateKey.contains("BEGIN")) {
            var githubService = new GitHubInstallationTokenService(appId, privateKey, installationId);
            githubService.register();
            LOGGER.info("GitHub installation token service configured");
        } else {
            LOGGER.info("GitHub App not configured (github.app.id or github.app.private_key missing), GitHub token tool will be unavailable");
        }
    }
}
