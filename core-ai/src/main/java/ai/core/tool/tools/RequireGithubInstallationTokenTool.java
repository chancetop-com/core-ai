package ai.core.tool.tools;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.tool.github.GitHubTokenProvider;
import ai.core.tool.github.GitHubTokenProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public final class RequireGithubInstallationTokenTool extends ToolCall {

    public static final String TOOL_NAME = "require_github_installation_token";

    private static final Logger LOGGER = LoggerFactory.getLogger(RequireGithubInstallationTokenTool.class);

    private static final String TOOL_DESC = """
            Request a temporary GitHub installation access token for a specific repository.
            This token is required before the agent can clone, modify, or create pull requests
            on repositories within the GitHub App installation scope.

            The returned token is valid for 1 hour and inherits all permissions granted to the GitHub App installation.
            Use it with:
            - git clone: git clone https://x-access-token:{token}@github.com/{owner}/{repo}.git
            - gh auth:  gh auth login --with-token <<< "{token}" (then use gh pr create, etc.)
            - git push:  git remote set-url origin https://x-access-token:{token}@github.com/{owner}/{repo}.git

            IMPORTANT: The token is SENSITIVE — never echo it, log it, or include it in commit messages.
            Always use it via environment variable or direct command substitution where possible.
            """;

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(GitHubTokenProvider tokenProvider) {
        return new Builder().tokenProvider(tokenProvider);
    }

    private final GitHubTokenProvider tokenProvider;

    private RequireGithubInstallationTokenTool(GitHubTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    public ToolCallResult execute(String arguments) {
        long startTime = System.currentTimeMillis();
        try {
            var args = parseArguments(arguments);
            String repo = getStringValue(args, "repo");
            if (repo == null || repo.isBlank()) {
                return ToolCallResult.failed("'repo' is required (e.g. 'owner/repo')");
            }
            var provider = tokenProvider != null ? tokenProvider : GitHubTokenProviderRegistry.getProvider();
            if (provider == null) {
                return ToolCallResult.failed("GitHub token provider is not configured on this server. "
                        + "Please configure github.app.id and github.app.private_key in agent.properties.");
            }
            String token = provider.getInstallationToken(repo);
            LOGGER.info("GitHub installation token generated for repo: {}", repo);
            return ToolCallResult.completed(formatResult(token, repo))
                    .withDuration(System.currentTimeMillis() - startTime)
                    .withStats("repo", repo);
        } catch (Exception e) {
            LOGGER.warn("Failed to generate GitHub installation token: {}", e.getMessage());
            return ToolCallResult.failed("Failed to generate GitHub installation token: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private String formatResult(String token, String repo) {
        return "GitHub installation token obtained (valid for 1 hour).%n%n"
                + "Token: %s%n%n"
                + "Clone with:%n  git clone https://x-access-token:%s@github.com/%s.git%n%n"
                + "Or configure gh CLI:%n  echo \"%s\" | gh auth login --with-token%n%n"
                + "After cloning, to create a PR:%n  gh pr create --title \"PR title\" --body \"PR description\"%n%n"
                + "IMPORTANT: Do NOT echo or log the token. Use it directly in commands.%n".formatted(token, token, repo, token.replace("\"", "\\\""));
    }

    public static class Builder extends ToolCall.Builder<Builder, RequireGithubInstallationTokenTool> {
        private GitHubTokenProvider tokenProvider;

        public Builder tokenProvider(GitHubTokenProvider tokenProvider) {
            this.tokenProvider = tokenProvider;
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public RequireGithubInstallationTokenTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "repo",
                            "Full repository name in owner/repo format (e.g. 'chancetop-com/core-ai')").required()
            ));
            var tool = new RequireGithubInstallationTokenTool(tokenProvider);
            build(tool);
            return tool;
        }
    }
}
