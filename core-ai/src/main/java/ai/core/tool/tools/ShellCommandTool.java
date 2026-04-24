package ai.core.tool.tools;

import ai.core.AgentRuntimeException;
import ai.core.agent.ExecutionContext;
import ai.core.agent.Task;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.tool.async.AsyncToolTaskExecutor;
import ai.core.utils.InputStreamUtil;
import ai.core.utils.ShellUtil;
import ai.core.utils.SystemUtil;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author stephen
 */
public class ShellCommandTool extends ToolCall {
    public static final String TOOL_NAME = "run_bash_command";

    private static final int DEFAULT_TIMEOUT_MILLISECONDS = 2 * 60 * 1000;
    private static final long ASYNC_TIMEOUT_SECONDS = 600;
    private static final Logger LOGGER = LoggerFactory.getLogger(ShellCommandTool.class);
    private static final String TOOL_DESC = Strings.format("""
            Executes a given bash command and returns its output.
            
            The working directory persists between commands, but shell state does not. The shell environment is initialized from the user's profile (bash or zsh).
            
            IMPORTANT: Avoid using this tool to run `cat`, `head`, `tail`, `sed`, `awk`, or `echo` commands, unless explicitly instructed or after you have verified that a dedicated tool cannot accomplish your task. Instead, use the appropriate dedicated tool as this will provide a much better experience for the user:
            
             - Read files: Use {} (NOT cat/head/tail)
             - Edit files: Use {} (NOT sed/awk)
             - Write files: Use {} (NOT echo >/cat <<EOF)
             - Communication: Output text directly (NOT echo/printf)
            While the {} tool can do similar things, it’s better to use the built-in tools as they provide a better user experience and make it easier to review tool calls and give permission.
            
            # Instructions
             - If your command will create new directories or files, first use this tool to run `ls` to verify the parent directory exists and is the correct location.
             - Always quote file paths that contain spaces with double quotes in your command (e.g., cd "path with spaces/file.txt")
             - Try to maintain your current working directory throughout the session by using absolute paths and avoiding usage of `cd`. You may use `cd` if the User explicitly requests it. In particular, never prepend `cd <current-directory>` to a `git` command — `git` already operates on the current working tree, and the compound triggers a permission prompt.
             - You may specify an optional timeout in milliseconds (up to 600000ms / 10 minutes). By default, your command will timeout after {}ms ({} minutes).
             - you can use the `mode` parameter indicates whether this is a read or write operation.
                - "read": Only reads data, no modifications (e.g., ls, cat, grep, find without -delete). Permission may be auto-approved.
                - "write": Modifies files or system state (e.g., rm, mkdir, echo >, sed -i). Requires explicit approval.
             - You can use the `run_in_background` parameter to run the command in the background. Only use this if you don't need the result immediately and are OK being notified when the command completes later. You do not need to check the output right away - you'll be notified when it finishes. You do not need to use '&' at the end of the command when using this parameter.
             - When issuing multiple commands:
              - If the commands are independent and can run in parallel, make multiple Bash tool calls in a single message. Example: if you need to run "git status" and "git diff", send a single message with two Bash tool calls in parallel.
              - If the commands depend on each other and must run sequentially, use a single Bash call with '&&' to chain them together.
              - Use ';' only when you need to run commands sequentially but don't care if earlier commands fail.
              - DO NOT use newlines to separate commands (newlines are ok in quoted strings).
             - For git commands:
              - Prefer to create a new commit rather than amending an existing commit.
              - Before running destructive operations (e.g., git reset --hard, git push --force, git checkout --), consider whether there is a safer alternative that achieves the same goal. Only use destructive operations when they are truly the best approach.
              - Never skip hooks (--no-verify) or bypass signing (--no-gpg-sign, -c commit.gpgsign=false) unless the user has explicitly asked for it. If a hook fails, investigate and fix the underlying issue.
             - Avoid unnecessary `sleep` commands:
              - Do not sleep between commands that can run immediately — just run them.
              - If your command is long running and you would like to be notified when it finishes — use `run_in_background`. No sleep needed.
              - Do not retry failing commands in a sleep loop — diagnose the root cause.
              - If waiting for a background task you started with `run_in_background`, you will be notified when it completes — do not poll.
              - If you must poll an external process, use a check command (e.g. `gh run view`) rather than sleeping first.
              - If you must sleep, keep the duration short to avoid blocking the user.
             - When using `find -regex` with alternation, put the longest alternative first. Example: use `'.*\\.\\(tsx\\|ts\\)'` not `'.*\\.\\(ts\\|tsx\\)'` — the second form silently skips `.tsx` files.
            
            
            # Committing changes with git
            
            Only create commits when requested by the user. If unclear, ask first. When the user asks you to create a new git commit, follow these steps carefully:
            
            You can call multiple tools in a single response. When multiple independent pieces of information are requested and all commands are likely to succeed, run multiple tool calls in parallel for optimal performance. The numbered steps below indicate which commands should be batched in parallel.
            
            Git Safety Protocol:
            - NEVER update the git config
            - NEVER run destructive git commands (push --force, reset --hard, checkout ., restore ., clean -f, branch -D) unless the user explicitly requests these actions. Taking unauthorized destructive actions is unhelpful and can result in lost work, so it's best to ONLY run these commands when given direct instructions\s
            - NEVER skip hooks (--no-verify, --no-gpg-sign, etc) unless the user explicitly requests it
            - NEVER run force push to main/master, warn the user if they request it
            - CRITICAL: Always create NEW commits rather than amending, unless the user explicitly requests a git amend. When a pre-commit hook fails, the commit did NOT happen — so --amend would modify the PREVIOUS commit, which may result in destroying work or losing previous changes. Instead, after hook failure, fix the issue, re-stage, and create a NEW commit
            - When staging files, prefer adding specific files by name rather than using "git add -A" or "git add .", which can accidentally include sensitive files (.env, credentials) or large binaries
            - NEVER commit changes unless the user explicitly asks you to. It is VERY IMPORTANT to only commit when explicitly asked, otherwise the user will feel that you are being too proactive
            
            1. Run the following bash commands in parallel, each using the {} tool:
              - Run a git status command to see all untracked files. IMPORTANT: Never use the -uall flag as it can cause memory issues on large repos.
              - Run a git diff command to see both staged and unstaged changes that will be committed.
              - Run a git log command to see recent commit messages, so that you can follow this repository's commit message style.
            2. Analyze all staged changes (both previously staged and newly added) and draft a commit message:
              - Summarize the nature of the changes (eg. new feature, enhancement to an existing feature, bug fix, refactoring, test, docs, etc.). Ensure the message accurately reflects the changes and their purpose (i.e. "add" means a wholly new feature, "update" means an enhancement to an existing feature, "fix" means a bug fix, etc.).
              - Do not commit files that likely contain secrets (.env, credentials.json, etc). Warn the user if they specifically request to commit those files
              - Draft a concise (1-2 sentences) commit message that focuses on the "why" rather than the "what"
              - Ensure it accurately reflects the changes and their purpose
            3. Run the following commands in parallel:
               - Add relevant untracked files to the staging area.
               - Create the commit with a message ending with:
               Co-Authored-By: core-ai-cli <noreply@chancetop.com>
               - Run git status after the commit completes to verify success.
               Note: git status depends on the commit completing, so run it sequentially after the commit.
            4. If the commit fails due to pre-commit hook: fix the issue and create a NEW commit
            
            Important notes:
            - NEVER run additional commands to read or explore code, besides git bash commands
            - NEVER use the {} or Agent tools
            - DO NOT push to the remote repository unless the user explicitly asks you to do so
            - IMPORTANT: Never use git commands with the -i flag (like git rebase -i or git add -i) since they require interactive input which is not supported.
            - IMPORTANT: Do not use --no-edit with git rebase commands, as the --no-edit flag is not a valid option for git rebase.
            - If there are no changes to commit (i.e., no untracked files and no modifications), do not create an empty commit
            - In order to ensure good formatting, ALWAYS pass the commit message via a HEREDOC, a la this example:
            <example>
            git commit -m "$(cat <<'EOF'
               Commit message here.
            
               Co-Authored-By: core-ai-cli <noreply@chancetop.com>
               EOF
               )"
            </example>
            
            # Creating pull requests
            Use the gh command via the {} tool for ALL GitHub-related tasks including working with issues, pull requests, checks, and releases. If given a Github URL use the gh command to get the information needed.
            
            IMPORTANT: When the user asks you to create a pull request, follow these steps carefully:
            
            1. Run the following bash commands in parallel using the {} tool, in order to understand the current state of the branch since it diverged from the main branch:
               - Run a git status command to see all untracked files (never use -uall flag)
               - Run a git diff command to see both staged and unstaged changes that will be committed
               - Check if the current branch tracks a remote branch and is up to date with the remote, so you know if you need to push to the remote
               - Run a git log command and `git diff [base-branch]...HEAD` to understand the full commit history for the current branch (from the time it diverged from the base branch)
            2. Analyze all changes that will be included in the pull request, making sure to look at all relevant commits (NOT just the latest commit, but ALL commits that will be included in the pull request!!!), and draft a pull request title and summary:
               - Keep the PR title short (under 70 characters)
               - Use the description/body for details, not the title
            3. Run the following commands in parallel:
               - Create new branch if needed
               - Push to remote with -u flag if needed
               - Create PR using gh pr create with the format below. Use a HEREDOC to pass the body to ensure correct formatting.
            <example>
            gh pr create --title "the pr title" --body "$(cat <<'EOF'
            ## Summary
            <1-3 bullet points>
            
            ## Test plan
            [Bulleted markdown checklist of TODOs for testing the pull request...]
            
            🤖 Generated with core-ai-cli
            EOF
            )"
            </example>
            
            Important:
            - DO NOT use the {} or Agent tools
            - Return the PR URL when you're done, so the user can see it
            
            # Other common operations
            - View comments on a Github PR: gh api repos/foo/bar/pulls/123/comments
            """, ReadFileTool.TOOL_NAME, EditFileTool.TOOL_NAME, WriteFileTool.TOOL_NAME, TOOL_NAME, DEFAULT_TIMEOUT_MILLISECONDS, DEFAULT_TIMEOUT_MILLISECONDS / 60000, WriteTodosTool.WT_TOOL_NAME, TOOL_NAME, WriteTodosTool.WT_TOOL_NAME, TOOL_NAME, TOOL_NAME);

    public static Builder builder() {
        return new Builder();
    }

    public static String exec(List<String> commands, String workdir, long timeout) {
        var dir = workdir;
        if (Strings.isBlank(dir)) dir = core.framework.util.Files.tempDir().toAbsolutePath().toString();

        var workDir = new File(dir);
        if (!workDir.exists()) {
            String error = "Error: workspace directory does not exist: " + dir;
            LOGGER.debug(error);
            return error;
        }
        if (!workDir.isDirectory()) {
            String error = "Error: workspace path is not a directory: " + dir;
            LOGGER.debug(error);
            return error;
        }

        var pb = new ProcessBuilder(commands);
        pb.directory(workDir);
        pb.redirectErrorStream(true);

        try {
            LOGGER.debug("Executing shell command in directory {}: {}", dir, String.join(" ", commands));
            var process = pb.start();

            var timedOut = waitFor(process, timeout);

            if (timedOut) {
                process.destroyForcibly();
                waitFor(process);
                return handleTimeout(process, timeout);
            }

            var outputLines = InputStreamUtil.readStream(process.getInputStream());
            var exitCode = process.exitValue();

            if (exitCode != 0) {
                String error = "Command exited with code " + exitCode + ":\n" + String.join("\n", outputLines);
                LOGGER.debug(error);
                return error;
            }

            LOGGER.debug("Shell command executed successfully, output: {} lines", outputLines.size());
            if (!outputLines.isEmpty()) {
                LOGGER.debug("Command output:\n{}", String.join("\n", outputLines));
            }
            return outputLines.isEmpty() ? "" : String.join("\n", outputLines);
        } catch (Exception e) {
            var error = "Command execution failed: " + e.getClass().getSimpleName() + " - " + e.getMessage();
            LOGGER.error(error, e);
            return error;
        }
    }

    private static String handleTimeout(Process process, long timeout) {
        String partial = "";
        try {
            var errorLines = InputStreamUtil.readStream(process.getInputStream());
            partial = String.join("\n", errorLines);
        } catch (IOException ignored) {
            // stream may already be closed after destroyForcibly
        }
        var error = "Command timed out after " + timeout + " seconds\nPlease check your command and workspace dir\n" + partial;
        LOGGER.debug(error);
        return error;
    }

    public static String execScript(Path scriptPath, String workdir, long timeout) {
        if (!Files.exists(scriptPath)) {
            return "Error: Script file does not exist: " + scriptPath;
        }
        if (!Files.isReadable(scriptPath)) {
            return "Error: Script file is not readable: " + scriptPath;
        }

        var dir = workdir;
        if (Strings.isBlank(dir)) {
            dir = scriptPath.getParent() != null
                    ? scriptPath.getParent().toAbsolutePath().toString()
                    : core.framework.util.Files.tempDir().toAbsolutePath().toString();
        }

        LOGGER.debug("Executing shell script from path: {}", scriptPath.toAbsolutePath());

        var shellPrefix = ShellUtil.getPreferredShellCommandPrefix(SystemUtil.detectPlatform()).trim();
        var prefixParts = shellPrefix.split(" ");
        var commands = new ArrayList<>(Arrays.asList(prefixParts));
        commands.add(scriptPath.toAbsolutePath().toString());

        return exec(commands, dir, timeout);
    }

    private static void waitFor(Process process) {
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean waitFor(Process process, long timeout) {
        boolean timedOut;
        try {
            timedOut = !process.waitFor(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            timedOut = true;
        }
        return timedOut;
    }

    @Override
    public ToolCallResult execute(String text, ExecutionContext context) {
        return doExecute(text, context);
    }

    @Override
    public ToolCallResult execute(String text) {
        throw new AgentRuntimeException("SHELL_TOOL_FAILED", "run bash  requires ExecutionContext");
    }

    private ToolCallResult doExecute(String text, ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        try {
            var argsMap = parseArguments(text);
            var command = getStringValue(argsMap, "command");
            var description = getStringValue(argsMap, "description");
            var timeMilliseconds = argsMap.get("timeout") == null ? DEFAULT_TIMEOUT_MILLISECONDS : (Integer) argsMap.get("timeout");
            var timeoutSeconds = timeMilliseconds / 1000;
            var runInBackground = Boolean.TRUE.equals(argsMap.get("run_in_background"));
            var workspaceDir = context.getCustomVariable("workspace") == null ? getStringValue(argsMap, "workspace") : (String) context.getCustomVariable("workspace");
            var shellPrefix = ShellUtil.getPreferredShellCommandPrefix(SystemUtil.detectPlatform()).trim();
            var prefixParts = shellPrefix.split(" ");
            var taskManager = context.getTaskManager();
            var commands = new ArrayList<>(Arrays.asList(prefixParts));
            commands.add(command);
            if (runInBackground && taskManager != null) {
                var taskId = UUID.randomUUID().toString().replace("-", "");
                var handle = taskManager.submit(taskId, () -> exec(commands, workspaceDir, timeoutSeconds));
                taskManager.register(new Task(taskId, description, context.getTaskId(), handle.future(), context));
                return ToolCallResult.asyncLaunched(taskId, buildAsyncLaunchedNotificationXml(taskId, handle.outputRef(), description))
                        .withDuration(System.currentTimeMillis() - startTime);
            } else {
                return ToolCallResult.completed(exec(commands, workspaceDir, timeoutSeconds))
                        .withDuration(System.currentTimeMillis() - startTime)
                        .withStats("command", command != null ? (command.length() > 50 ? command.substring(0, 50) + "..." : command) : null);
            }
        } catch (Exception e) {
            var error = "Failed to parse shell command arguments: " + e.getMessage();
            return ToolCallResult.failed(error, e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private String buildAsyncLaunchedNotificationXml(String taskId, String outputRef, String description) {
        var outputRefXml = outputRef != null ? "<output-ref>" + outputRef + "</output-ref>\n" : "";
        var reminder = """
                  Async bash launched successfully.
                  The bash packaged as a task is working in the background. You will be notified automatically when it completes.
                  task id: %s (internal ID - do not mention to user)
                  Do not duplicate this task's work — avoid working with the same files or topics it is using. Work on non-overlapping tasks, or briefly tell the user what you launched and end
                  your response.
                  output_file: %s
                  If asked, you can check progress before completion by using %s or %s tail on the output file.
                """.formatted(taskId, outputRef, ReadFileTool.TOOL_NAME, ShellCommandTool.TOOL_NAME);
        return """
                <task-notification>
                <task-id>%s</task-id>
                <task-type>%s</task-type>
                <task-description>%s</task-description>
                <status>%s</status>
                %s
                <system-reminder>%s</system-reminder>
                </task-notification>
                """.formatted(taskId, "bash", description, "async_launched", outputRefXml, reminder);
    }

    private ToolCallResult executeAsync(String command, Path scriptPath, String workspaceDir, long startTime) {
        var asyncExecutor = AsyncToolTaskExecutor.getInstance();
        var taskId = asyncExecutor.submit("sh", TOOL_NAME, () -> {
            if (!Strings.isBlank(command)) {
                var shellPrefix = ShellUtil.getPreferredShellCommandPrefix(SystemUtil.detectPlatform()).trim();
                var prefixParts = shellPrefix.split(" ");
                var commands = new ArrayList<>(Arrays.asList(prefixParts));
                commands.add(command);
                return exec(commands, workspaceDir, ASYNC_TIMEOUT_SECONDS);
            } else {
                return execScript(scriptPath, workspaceDir, ASYNC_TIMEOUT_SECONDS);
            }
        });

        var message = scriptPath != null
                ? "Executing script: " + scriptPath
                : "Executing command: " + (command.length() > 50 ? command.substring(0, 50) + "..." : command);

        return ToolCallResult.pending(taskId, message)
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("async", true);
    }

    @Override
    public ToolCallResult poll(String taskId) {
        return AsyncToolTaskExecutor.getInstance().poll(taskId);
    }

    @Override
    public ToolCallResult cancel(String taskId) {
        return AsyncToolTaskExecutor.getInstance().cancel(taskId);
    }

    public static class Builder extends ToolCall.Builder<Builder, ShellCommandTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public ShellCommandTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "workspace", "Working directory for command execution"),
                    ToolCallParameters.ParamSpec.of(Integer.class, "timeout", "Optional timeout in milliseconds (max 600000)"),
                    ToolCallParameters.ParamSpec.of(String.class, "command", "The command to execute").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "description", "Clear, concise description of what this command does in active voice. Never use words like \\\"complex\\\" or \\\"risk\\\" in the description - just describe what it does.\\n\\nFor simple commands (git, npm, standard CLI tools), keep it brief (5-10 words):\\n- ls \\u2192 \\\"List files in current directory\\\"\\n- git status \\u2192 \\\"Show working tree status\\\"\\n- npm install \\u2192 \\\"Install package dependencies\\\"\\n\\nFor commands that are harder to parse at a glance (piped commands, obscure flags, etc.), add enough context to clarify what it does:\\n- find . -name \\\"*.tmp\\\" -exec rm {} \\\\; \\u2192 \\\"Find and delete all .tmp files recursively\\\"\\n- git reset --hard origin/main \\u2192 \\\"Discard all local changes and match remote main\\\"\\n- curl -s url | jq '.data[]' \\u2192 \\\"Fetch JSON from URL and extract data array elements\\"),
                    ToolCallParameters.ParamSpec.of(String.class, "mode", "Operation mode: 'read' for read-only operations, 'write' for operations that modify files or system state. This helps with permission control.").required(),
                    ToolCallParameters.ParamSpec.of(Boolean.class, "run_in_background", "Set to true to run this command in the background. Use Read to read the output later.")
            ));
            var tool = new ShellCommandTool();
            build(tool);
            return tool;
        }
    }
}
