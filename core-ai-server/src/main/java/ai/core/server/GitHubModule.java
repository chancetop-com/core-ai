package ai.core.server;

import ai.core.server.github.DynamicGitHubTokenProvider;
import ai.core.server.settings.SystemSettingsService;
import ai.core.tool.github.GitHubTokenProvider;
import core.framework.module.Module;

/**
 * Binds a lazily resolved {@link GitHubTokenProvider} backed by Mongo system settings.
 * Configuration changes take effect immediately on all replicas without restart.
 *
 * @author stephen
 */
public class GitHubModule extends Module {
    @Override
    protected void initialize() {
        bind(GitHubTokenProvider.class, new DynamicGitHubTokenProvider(bean(SystemSettingsService.class)));
    }
}
