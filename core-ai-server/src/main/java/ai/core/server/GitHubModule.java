package ai.core.server;

import ai.core.server.github.GitHubInstallationTokenService;
import ai.core.server.settings.SystemSettingsService;
import ai.core.tool.github.GitHubTokenProvider;
import ai.core.tool.github.GitHubTokenProviderRegistry;
import core.framework.module.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class GitHubModule extends Module {
    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubModule.class);

    private boolean githubBound;

    @Override
    protected void initialize() {
        // GitHub App config comes from Mongo system settings, available only after startup hooks initialize
        onStartup(() -> {
            configureGitHub();
            bean(SystemSettingsService.class).onSettingsChanged(this::configureGitHub);
        });
    }

    private void configureGitHub() {
        var settings = bean(SystemSettingsService.class);
        var appId = readSetting(settings::githubAppId);
        var installationIdValue = readSetting(settings::githubAppInstallationId);
        var privateKey = readSetting(settings::githubAppPrivateKey);
        Long installationId = null;
        if (installationIdValue != null && !installationIdValue.isBlank()) {
            try {
                installationId = Long.valueOf(installationIdValue.trim());
            } catch (NumberFormatException e) {
                LOGGER.warn("invalid github app installation id: {}", installationIdValue);
            }
        }
        if (appId != null && installationId != null && privateKey != null && !appId.isBlank() && !privateKey.isBlank() && privateKey.contains("BEGIN")) {
            var githubService = new GitHubInstallationTokenService(appId, privateKey, installationId);
            if (!githubBound) {
                bind(GitHubTokenProvider.class, githubService);
                githubBound = true;
            }
            githubService.register();
            LOGGER.info("GitHub installation token service configured (appId={}, installationId={})", appId, installationId);
        } else {
            GitHubTokenProviderRegistry.clear();
            LOGGER.info("GitHub App not configured (github.app.id or github.app.private_key missing), GitHub token tool will be unavailable");
        }
    }

    private String readSetting(java.util.function.Supplier<String> getter) {
        try {
            return getter.get();
        } catch (Exception e) {
            LOGGER.warn("failed to read system settings, github token tool will be unavailable", e);
            return null;
        }
    }
}
