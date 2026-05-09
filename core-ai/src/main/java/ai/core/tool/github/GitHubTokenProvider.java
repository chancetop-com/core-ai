package ai.core.tool.github;

/**
 * @author stephen
 */
public interface GitHubTokenProvider {
    String getInstallationToken(String repoFullName);
}
