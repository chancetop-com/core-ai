package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.tool.async.AsyncToolTaskExecutor;
import ai.core.utils.InputStreamUtil;
import ai.core.utils.ShellUtil;
import ai.core.utils.SystemUtil;
import core.framework.json.JSON;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author stephen
 */
public class ShellCommandTool extends ToolCall {
    public static final String TOOL_NAME = "run_bash_command";

    private static final long DEFAULT_TIMEOUT_SECONDS = 30;
    private static final long ASYNC_TIMEOUT_SECONDS = 600;
    private static final Logger LOGGER = LoggerFactory.getLogger(ShellCommandTool.class);
    private static final String TOOL_DESC = """
            Executes a given bash command or shell script in a persistent shell session with optional
            timeout, ensuring proper handling and security measures.


            Before executing the command, please follow these steps:


            1. Directory Verification:
             - If the command will create new directories or files, first use the LS tool to verify the parent directory exists and is the correct location
             - For example, before running "mkdir foo/bar", first use LS to check that "foo" exists and is the intended parent directory

            2. Command Execution:
             - Provide a command string via 'command' parameter OR a script file path via 'script_path' parameter.
             - If both 'command' and 'script_path' are provided, 'command' takes precedence.
             - Always quote file paths that contain spaces with double quotes (e.g., cd "path with spaces/file.txt")
             - Examples of proper quoting:
               - cd "/Users/name/My Documents" (correct)
               - cd /Users/name/My Documents (incorrect - will fail)
               - python "/path/with spaces/script.py" (correct)
               - python /path/with spaces/script.py (incorrect - will fail)
             - After ensuring proper quoting, execute the command.
             - Capture the output of the command.

            Usage notes:
            - Either 'command' or 'script_path' parameter is required.
            - You can specify an optional timeout in milliseconds (up to 600000ms / 10 minutes). If not specified, commands will timeout after 30 seconds.
            - Set 'async' to true for long-running commands. Use 'async_task_output' tool to check progress.
            - It is very helpful if you write a clear, concise description of what this command does in 5-10 words.
            - If the output exceeds 30000 characters, output will be truncated before being returned to you.
            - VERY IMPORTANT: You MUST avoid using search commands like `find` and `grep`. Instead use Grep, Glob, or Task to search. You MUST avoid read tools like `cat`, `head`, `tail`, and `ls`, and use Read and LS to read files.
            - If you _still_ need to run `grep`, STOP. ALWAYS USE ripgrep at `rg` first, which all ${PRODUCT_NAME} users have pre-installed.
            - When issuing multiple commands, use the ';' or '&&' operator to separate them. DO NOT use newlines (newlines are ok in quoted strings).
            - Try to maintain your current working directory throughout the session by using absolute paths and avoiding usage of `cd`. You may use `cd` if the User explicitly requests it.
              <good-example>
              pytest /foo/bar/tests
              </good-example>
              <bad-example>
              cd /foo/bar && pytest tests
              </bad-example>




            # Committing changes with git


            When the user asks you to create a new git commit, follow these steps
            carefully:


            1. You have the capability to call multiple tools in a single response. When
            multiple independent pieces of information are requested, batch your tool
            calls together for optimal performance. ALWAYS run the following bash commands
            in parallel, each using the Bash tool:
            - Run a git status command to see all untracked files.
            - Run a git diff command to see both staged and unstaged changes that will be committed.
            - Run a git log command to see recent commit messages, so that you can follow this repository's commit message style.
            2. Analyze all staged changes (both previously staged and newly added) and
            draft a commit message:
            - Summarize the nature of the changes (eg. new feature, enhancement to an existing feature, bug fix, refactoring, test, docs, etc.). Ensure the message accurately reflects the changes and their purpose (i.e. "add" means a wholly new feature, "update" means an enhancement to an existing feature, "fix" means a bug fix, etc.).
            - Check for any sensitive information that shouldn't be committed
            - Draft a concise (1-2 sentences) commit message that focuses on the "why" rather than the "what"
            - Ensure it accurately reflects the changes and their purpose
            3. You have the capability to call multiple tools in a single response. When
            multiple independent pieces of information are requested, batch your tool
            calls together for optimal performance. ALWAYS run the following commands in
            parallel:
             - Add relevant untracked files to the staging area.
             - Create the commit with a message ending with:

             Co-Authored-By: CoreAI <noreply@chancetop.com>
             - Run git status to make sure the commit succeeded.
            4. If the commit fails due to pre-commit hook changes, retry the commit ONCE
            to include these automated changes. If it fails again, it usually means a
            pre-commit hook is preventing the commit. If the commit succeeds but you
            notice that files were modified by the pre-commit hook, you MUST amend your
            commit to include them.


            Important notes:

            - NEVER update the git config

            - NEVER run additional commands to read or explore code, besides git bash
            commands

            - NEVER use the TodoWrite or Task tools

            - DO NOT push to the remote repository unless the user explicitly asks you to
            do so

            - IMPORTANT: Never use git commands with the -i flag (like git rebase -i or
            git add -i) since they require interactive input which is not supported.

            - If there are no changes to commit (i.e., no untracked files and no
            modifications), do not create an empty commit

            - In order to ensure good formatting, ALWAYS pass the commit message via a
            HEREDOC, a la this example:

            <example>

            git commit -m "$(cat <<'EOF'
             Commit message here.

             Co-Authored-By: CoreAI <noreply@chancetop.com>
             EOF
             )"
            </example>


            # Creating pull requests

            Use the gh command via the Bash tool for ALL GitHub-related tasks including
            working with issues, pull requests, checks, and releases. If given a Github
            URL use the gh command to get the information needed.


            IMPORTANT: When the user asks you to create a pull request, follow these steps
            carefully:


            1. You have the capability to call multiple tools in a single response. When
            multiple independent pieces of information are requested, batch your tool
            calls together for optimal performance. ALWAYS run the following bash commands
            in parallel using the Bash tool, in order to understand the current state of
            the branch since it diverged from the main branch:
             - Run a git status command to see all untracked files
             - Run a git diff command to see both staged and unstaged changes that will be committed
             - Check if the current branch tracks a remote branch and is up to date with the remote, so you know if you need to push to the remote
             - Run a git log command and `git diff [base-branch]...HEAD` to understand the full commit history for the current branch (from the time it diverged from the base branch)
            2. Analyze all changes that will be included in the pull request, making sure
            to look at all relevant commits (NOT just the latest commit, but ALL commits
            that will be included in the pull request!!!), and draft a pull request
            summary

            3. You have the capability to call multiple tools in a single response. When
            multiple independent pieces of information are requested, batch your tool
            calls together for optimal performance. ALWAYS run the following commands in
            parallel:
             - Create new branch if needed
             - Push to remote with -u flag if needed
             - Create PR using gh pr create with the format below. Use a HEREDOC to pass the body to ensure correct formatting.
            <example>

            gh pr create --title "the pr title" --body "$(cat <<'EOF'

            ## Summary

            <1-3 bullet points>


            ## Test plan

            [Checklist of TODOs for testing the pull request...]

            EOF

            )"

            </example>


            Important:

            - NEVER update the git config

            - DO NOT use the TodoWrite or Task tools

            - Return the PR URL when you're done, so the user can see it


            # Other common operations

            - View comments on a Github PR: gh api repos/foo/bar/pulls/123/comments
            """;

    public static Builder builder() {
        return new Builder();
    }

    public static String exec(List<String> commands, String workdir, long timeout) {
        var dir = workdir;
        if (Strings.isBlank(dir)) dir = core.framework.util.Files.tempDir().toAbsolutePath().toString();

        var workDir = new File(dir);
        if (!workDir.exists()) {
            String error = "Error: workspace directory does not exist: " + dir;
            LOGGER.warn(error);
            return error;
        }
        if (!workDir.isDirectory()) {
            String error = "Error: workspace path is not a directory: " + dir;
            LOGGER.warn(error);
            return error;
        }

        var pb = new ProcessBuilder(commands);
        pb.directory(workDir);
        pb.redirectErrorStream(true);

        try {
            LOGGER.info("Executing shell command in directory {}: {}", dir, String.join(" ", commands));
            var process = pb.start();

            var timedOut = waitFor(process, timeout);

            if (timedOut) {
                process.destroyForcibly();
                waitFor(process);
                var errorLines = InputStreamUtil.readStream(process.getInputStream());
                var error = "Command timed out after " + timeout + " seconds\nPlease check your command and workspace dir\n" + String.join("\n", errorLines);
                LOGGER.warn(error);
                return error;
            }

            var outputLines = InputStreamUtil.readStream(process.getInputStream());
            var exitCode = process.exitValue();

            if (exitCode != 0) {
                String error = "Command exited with code " + exitCode + ":\n" + String.join("\n", outputLines);
                LOGGER.warn(error);
                return error;
            }

            LOGGER.info("Shell command executed successfully, output: {} lines", outputLines.size());
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

        LOGGER.info("Executing shell script from path: {}", scriptPath.toAbsolutePath());

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
        return doExecute(text);
    }

    @Override
    public ToolCallResult execute(String text) {
        return doExecute(text);
    }

    private ToolCallResult doExecute(String text) {
        long startTime = System.currentTimeMillis();
        try {
            var argsMap = JSON.fromJSON(Map.class, text);
            var workspaceDir = (String) argsMap.get("workspace_dir");
            var command = (String) argsMap.get("command");
            var scriptPathStr = (String) argsMap.get("script_path");
            var asyncMode = Boolean.TRUE.equals(argsMap.get("async"));

            if (Strings.isBlank(command) && Strings.isBlank(scriptPathStr)) {
                return ToolCallResult.failed("Error: Either 'command' or 'script_path' parameter is required")
                        .withDuration(System.currentTimeMillis() - startTime);
            }

            var scriptPath = scriptPathStr != null ? Path.of(scriptPathStr) : null;

            if (asyncMode) {
                return executeAsync(command, scriptPath, workspaceDir, startTime);
            }

            String result;
            if (!Strings.isBlank(command)) {
                var shellPrefix = ShellUtil.getPreferredShellCommandPrefix(SystemUtil.detectPlatform()).trim();
                var prefixParts = shellPrefix.split(" ");
                var commands = new ArrayList<>(Arrays.asList(prefixParts));
                commands.add(command);
                result = exec(commands, workspaceDir, DEFAULT_TIMEOUT_SECONDS);
            } else {
                result = execScript(scriptPath, workspaceDir, DEFAULT_TIMEOUT_SECONDS);
            }

            return ToolCallResult.completed(result)
                    .withDuration(System.currentTimeMillis() - startTime)
                    .withStats("command", command != null ? (command.length() > 50 ? command.substring(0, 50) + "..." : command) : null)
                    .withStats("scriptPath", scriptPathStr);
        } catch (Exception e) {
            var error = "Failed to parse shell command arguments: " + e.getMessage();
            LOGGER.error(error, e);
            return ToolCallResult.failed(error)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
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
                    ToolCallParameters.ParamSpec.of(String.class, "workspace_dir", "Working directory for command execution"),
                    ToolCallParameters.ParamSpec.of(String.class, "command", "Command string to execute. Either 'command' or 'script_path' is required."),
                    ToolCallParameters.ParamSpec.of(String.class, "script_path", "Path to a shell script file to execute. Either 'command' or 'script_path' is required."),
                    ToolCallParameters.ParamSpec.of(Boolean.class, "async", "Set to true to run the command asynchronously. Use 'async_task_output' tool to check progress.")
            ));
            var tool = new ShellCommandTool();
            build(tool);
            return tool;
        }
    }
}
