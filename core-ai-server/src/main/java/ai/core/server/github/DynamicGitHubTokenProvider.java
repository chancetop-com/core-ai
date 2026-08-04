package ai.core.server.github;

import ai.core.server.domain.SystemSettings;
import ai.core.server.settings.SystemSettingsProvider;
import ai.core.server.settings.SystemSettingsService;
import ai.core.tool.github.GitHubTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link GitHubTokenProvider} backed by Mongo system settings, resolved lazily on every call.
 * Configuration changes take effect immediately on all replicas without restart.
 *
 * @author stephen
 */
public class DynamicGitHubTokenProvider implements GitHubTokenProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(DynamicGitHubTokenProvider.class);
    static final String NOT_CONFIGURED_MESSAGE = "GitHub token provider is not configured on this server. "
            + "Please configure GitHub App settings in Admin UI (Settings -> System Configuration).";

    private static String value(String v) {
        return v == null ? "" : v.trim();
    }

    private final Provider provider;

    public DynamicGitHubTokenProvider(SystemSettingsService settings) {
        this.provider = new Provider(settings);
    }

    @Override
    public String getInstallationToken(String repoFullName) {
        var service = provider.get();
        if (service == null) throw new RuntimeException(NOT_CONFIGURED_MESSAGE);
        return service.getInstallationToken(repoFullName);
    }

    private static final class Provider extends SystemSettingsProvider<GitHubInstallationTokenService> {
        Provider(SystemSettingsService settings) {
            super(settings);
        }

        @Override
        protected String fingerprint(SystemSettings entity) {
            if (entity == null) return "";
            return value(entity.githubAppId) + "|" + value(entity.githubAppInstallationId) + "|" + value(entity.githubAppPrivateKey);
        }

        @Override
        protected GitHubInstallationTokenService build(SystemSettings entity) {
            if (entity == null) return null;
            var appId = value(entity.githubAppId);
            var installationIdValue = value(entity.githubAppInstallationId);
            var privateKey = settings.githubAppPrivateKey();
            if (appId.isEmpty() || installationIdValue.isEmpty()) return null;
            if (privateKey == null || privateKey.isBlank() || !privateKey.contains("BEGIN")) return null;
            Long installationId;
            try {
                installationId = Long.valueOf(installationIdValue.trim());
            } catch (NumberFormatException e) {
                LOGGER.warn("invalid github app installation id: {}", installationIdValue);
                return null;
            }
            return new GitHubInstallationTokenService(appId, privateKey, installationId);
        }
    }
}
