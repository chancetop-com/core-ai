package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.sandbox.Sandbox;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.tool.github.GitHubTokenProvider;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

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

            Calling this tool automatically configures the credentials into the execution environment
            (git credential store and gh CLI). Follow the instructions in the returned text and run
            git/gh commands directly — authentication is already handled. Do NOT run `gh auth login`.

            The token is valid for 1 hour and inherits all permissions granted to the GitHub App installation.
            """;

    private static final String SANDBOX_RESULT = "GitHub credentials configured in the sandbox (valid for 1 hour).%n%n"
            + "git and gh CLI are already authenticated. Use them directly:%n"
            + "  git clone https://github.com/%s.git%n"
            + "  git push%n"
            + "  gh pr create --title \"...\" --body \"...\"%n%n"
            + "Do NOT run `gh auth login`. Do NOT put any token in commands —%n"
            + "authentication is already handled by the environment.%n";

    private static final String INLINE_RESULT = "GitHub installation token obtained (valid for 1 hour).%n%n"
            + "Token: %s%n%n"
            + "Clone with:%n"
            + "  git clone https://x-access-token:%s@github.com/%s.git%n%n"
            + "Or configure gh CLI:%n"
            + "  echo \"%s\" | gh auth login --with-token%n%n"
            + "After cloning, to create a PR:%n"
            + "  gh pr create --title \"PR title\" --body \"PR description\"%n%n"
            + "There is NO pre-set environment variable containing this token. You MUST paste the%n"
            + "literal token value into commands exactly as shown above.%n"
            + "Do not print the token in output or commit messages.%n";

    private static final String INJECTION_SCRIPT = """
            set -e
            umask 077
            mkdir -p "$HOME/.config/gh"
            printf 'version: "1"\\n' > "$HOME/.config/gh/config.yml"
            printf 'https://x-access-token:%s@github.com\\n' '%s' > "$HOME/.git-credentials"
            git config --global credential.helper store
            printf 'github.com:\\n    user: x-access-token\\n    oauth_token: %s\\n    git_protocol: https\\n' '%s' > "$HOME/.config/gh/hosts.yml"
            echo OK
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
        return doExecute(arguments, null);
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        return doExecute(arguments, context);
    }

    private ToolCallResult doExecute(String arguments, ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        try {
            var args = parseArguments(arguments);
            String repo = getStringValue(args, "repo");
            if (repo == null || repo.isBlank()) {
                return ToolCallResult.failed("'repo' is required (e.g. 'owner/repo')");
            }
            var provider = tokenProvider;
            if (provider == null) {
                return ToolCallResult.failed("GitHub token provider is not configured on this server. "
                        + "Please configure GitHub App settings in Admin UI (Settings -> System Configuration).");
            }
            String token = provider.getInstallationToken(repo);
            LOGGER.info("GitHub installation token generated for repo: {}", repo);
            var sandbox = context == null ? null : context.getSandbox();
            if (sandbox != null) {
                var injected = injectIntoSandbox(sandbox, token, context);
                if (injected) {
                    return ToolCallResult.completed(SANDBOX_RESULT.formatted(repo))
                            .withDuration(System.currentTimeMillis() - startTime)
                            .withStats("repo", repo)
                            .withStats("injection", "sandbox");
                }
                LOGGER.warn("sandbox credential injection failed, falling back to inline token: repo={}", repo);
            }
            return ToolCallResult.completed(formatInlineResult(token, repo))
                    .withDuration(System.currentTimeMillis() - startTime)
                    .withStats("repo", repo)
                    .withStats("injection", "inline");
        } catch (Exception e) {
            LOGGER.warn("Failed to generate GitHub installation token: {}", e.getMessage());
            return ToolCallResult.failed("Failed to generate GitHub installation token: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private boolean injectIntoSandbox(Sandbox sandbox, String token, ExecutionContext context) {
        // installation tokens never contain single quotes, escape defensively anyway
        var escapedToken = token.replace("'", "'\\''");
        // literal replacement keeps real \n line endings for the Linux shell script (format strings
        // would be platform-dependent; %n becomes \r\n on Windows servers and breaks bash)
        var script = INJECTION_SCRIPT.replace("%s", escapedToken);
        var result = sandbox.execute(ShellCommandTool.TOOL_NAME, JsonUtil.toJson(Map.of("command", script)), context);
        return result.isCompleted() && result.getResult() != null && result.getResult().contains("OK");
    }

    private String formatInlineResult(String token, String repo) {
        return INLINE_RESULT.formatted(token, token, repo, token.replace("\"", "\\\""));
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
