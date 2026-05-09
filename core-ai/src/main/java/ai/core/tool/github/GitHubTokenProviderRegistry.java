package ai.core.tool.github;

/**
 * @author stephen
 */
public final class GitHubTokenProviderRegistry {
    private static GitHubTokenProvider provider;

    public static void setProvider(GitHubTokenProvider provider) {
        GitHubTokenProviderRegistry.provider = provider;
    }

    public static GitHubTokenProvider getProvider() {
        return provider;
    }

    public static void clear() {
        provider = null;
    }

    private GitHubTokenProviderRegistry() {
    }
}
