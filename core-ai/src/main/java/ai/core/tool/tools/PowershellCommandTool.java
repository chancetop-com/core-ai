package ai.core.tool.tools;

import ai.core.tool.ToolCallParameters;

/**
 * PowerShell-specific command tool for Windows environments.
 * Reuses {@link ShellCommandTool}'s execution logic but provides a
 * PowerShell-native name and description.
 *
 * @author Lim Chen
 */
public class PowershellCommandTool extends ShellCommandTool {
    public static final String POWERSHELL_TOOL_NAME = "run_powershell_command";

    private static final String TOOL_DESC_TEMPLATE = """
            Executes a PowerShell command in a persistent shell session with optional timeout, ensuring proper handling and security measures.

            Be aware: OS: ${os}

            All commands run in the current working directory by default. Use the `workspace` parameter if you need to run a command in a different directory. AVOID using `Set-Location <directory>; <command>` patterns — use `workspace` instead.

            IMPORTANT: Avoid using this tool to run `Get-ChildItem`, `Select-String`, `Get-Content`, or `Write-Output` commands, unless explicitly instructed or after you have verified that a dedicated tool cannot accomplish your task. Instead, use the appropriate dedicated tool as this will provide a much better experience for the user:

             - Read files: Use ${tool_read_file} (NOT Get-Content/cat/type)
             - Edit files: Use ${tool_edit_file} (NOT Set-Content/Out-File)
             - File search: Use ${tool_glob} (NOT Get-ChildItem/ls/dir)
             - Content search: Use ${tool_grep} (NOT Select-String/findstr)
             - Write files: Use ${tool_write_file} (NOT Out-File/Set-Content)
             - Communication: Output text directly (NOT Write-Output/echo)
            While the ${tool_shell} tool can do similar things, it's better to use the built-in tools as they provide a better user experience and make it easier to review tool calls and give permission.

            # Instructions
             - If your command will create new directories or files, first use this tool to run `Get-ChildItem` to verify the parent directory exists and is the correct location.
             - Always quote file paths that contain spaces with double quotes (e.g., Set-Location "path with spaces/file.txt")
             - Try to maintain your current working directory throughout the session by using absolute paths and avoiding usage of `Set-Location` / `cd`. You may use `cd` if the User explicitly requests it. In particular, never prepend `cd <current-directory>` to a `git` command — `git` already operates on the current working tree, and the compound triggers a permission prompt.
             - You may specify an optional timeout in milliseconds (up to 600000ms / 10 minutes). By default, your command will timeout after ${default_timeout_ms}ms (${default_timeout_minutes} minutes).
             - you can use the `mode` parameter indicates whether this is a read or write operation.
                - "read": Only reads data, no modifications (e.g., Get-ChildItem, Get-Content, Select-String). Permission may be auto-approved.
                - "write": Modifies files or system state (e.g., Remove-Item, New-Item, Set-Content). Requires explicit approval.
             - You can use the `run_in_background` parameter to run the command in the background. Only use this if you don't need the result immediately and are OK being notified when the command completes later. You do not need to check the output right away — you'll be notified when it finishes. You do not need to use `&` at the end of the command when using this parameter.
             - When issuing multiple commands:
              - If the commands are independent and can run in parallel, make multiple PowerShell tool calls in a single message. Example: if you need to run "git status" and "git diff", send a single message with two PowerShell tool calls in parallel.
              - If the commands depend on each other and must run sequentially, chain them with `; if ($?) { ... }` to conditionally run the next command only if the previous succeeded. Example: `git add .; if ($?) { git commit -m "..." }`
              - Use `;` when you need to run commands sequentially but don't care if earlier commands fail.
              - DO NOT use newlines to separate commands (newlines are ok in quoted strings).
             - For git commands:
              - Prefer to create a new commit rather than amending an existing commit.
              - Before running destructive operations (e.g., git reset --hard, git push --force, git checkout --), consider whether there is a safer alternative that achieves the same goal. Only use destructive operations when they are truly the best approach.
              - Never skip hooks (--no-verify) or bypass signing (--no-gpg-sign, -c commit.gpgsign=false) unless the user has explicitly asked for it. If a hook fails, investigate and fix the underlying issue.
             - Avoid unnecessary `Start-Sleep` commands:
              - Do not sleep between commands that can run immediately — just run them.
              - If your command is long running and you would like to be notified when it finishes — use `run_in_background`. No sleep needed.
              - Do not retry failing commands in a sleep loop — diagnose the root cause.
              - If waiting for a background task you started with `run_in_background`, you will be notified when it completes — do not poll.
              - If you must poll an external process, use a check command (e.g. `gh run view`) rather than sleeping first.
              - If you must sleep, keep the duration short to avoid blocking the user.

            # PowerShell-specific notes
             - Pipeline chain operators `&&` and `||` are NOT available. To run B only if A succeeds: `A; if ($?) { B }`. To chain unconditionally: `A; B`.
             - Ternary (`?:`), null-coalescing (`??`), and null-conditional (`?.`) operators are NOT available. Use `if`/`else` and explicit `$null -eq` checks instead.
             - Avoid `2>&1` on native executables. In PowerShell, redirecting a native command's stderr wraps each line in an ErrorRecord (NativeCommandError) and sets `$?` to `$false` even when the exe returned exit code 0. stderr is already captured for you — don't redirect it.
             - Default file encoding is UTF-16 LE (with BOM). When writing files other tools will read, pass `-Encoding UTF8` to `Out-File`/`Set-Content`.
             - `ConvertFrom-Json` returns a `PSCustomObject`, not a hashtable. `-AsHashtable` is not available.
             - To read any file, always prefer the dedicated ${tool_read_file} tool instead of `Get-Content`, `cat`, or `type`. The ${tool_read_file} tool handles all file types (text, images, PDFs) and automatically limits output size, preventing issues with large or growing files.
             - If you absolutely must use `Get-Content` to read a file (e.g., for piping or filtering the content), always pipe to `Select-Object -Last N` to limit the lines read. Avoid using `-Tail` or `-Last` directly on `Get-Content` — on large or actively-written log files, those parameters can cause `Get-Content` to hang. Instead use: `Get-Content -Path "..." | Select-Object -Last 500`

            # Committing changes with git

            Only create commits when requested by the user. If unclear, ask first. When the user asks you to create a new git commit, follow these steps carefully:

            You can call multiple tools in a single response. When multiple independent pieces of information are requested and all commands are likely to succeed, run multiple tool calls in parallel for optimal performance. The numbered steps below indicate which commands should be batched in parallel.

            Git Safety Protocol:
            - NEVER update the git config
            - NEVER run destructive git commands (push --force, reset --hard, checkout ., restore ., clean -f, branch -D) unless the user explicitly requests these actions. Taking unauthorized destructive actions is unhelpful and can result in lost work, so it's best to ONLY run these commands when given direct instructions
            - NEVER skip hooks (--no-verify, --no-gpg-sign, etc) unless the user explicitly requests it
            - NEVER run force push to main/master, warn the user if they request it
            - CRITICAL: Always create NEW commits rather than amending, unless the user explicitly requests a git amend. When a pre-commit hook fails, the commit did NOT happen — so --amend would modify the PREVIOUS commit, which may result in destroying work or losing previous changes. Instead, after hook failure, fix the issue, re-stage, and create a NEW commit
            - When staging files, prefer adding specific files by name rather than using "git add -A" or "git add .", which can accidentally include sensitive files (.env, credentials) or large binaries
            - NEVER commit changes unless the user explicitly asks you to. It is VERY IMPORTANT to only commit when explicitly asked, otherwise the user will feel that you are being too proactive

            1. Run the following commands in parallel, each using the ${tool_shell} tool:
              - Run a git status command to see all untracked files. IMPORTANT: Never use the -uall flag as it can cause memory issues on large repos.
              - Run a git diff command to see both staged and unstaged changes that will be committed.
              - Run a git log command to see recent commit messages, so that you can follow this repository's commit message style.
            2. Analyze all staged changes (both previously staged and newly added) and draft a commit message:
              - Summarize the nature of the changes (eg. new feature, enhancement to an existing feature, bug fix, refactoring, test, docs, etc.). Ensure the message accurately reflects the changes and their purpose (i.e. "add" means a wholly new feature, "update" means an enhancement to an existing feature, "fix" means a bug fix, etc.).
              - Do not commit files that likely contain secrets (.env, credentials.json, etc). Warn the user if they specifically request to commit those files
              - Draft a concise (1-2 sentences) commit message that focuses on the "why" rather than the "what"
            3. Run the following commands:
               - Add relevant untracked files to the staging area.
               - Create the commit with a message ending with:
               Co-Authored-By: core-ai-cli <noreply@chancetop.com>
               - Run git status after the commit completes to verify success.
               Note: git status depends on the commit completing, so run it sequentially after the commit.
            4. If the commit fails due to pre-commit hook: fix the issue and create a NEW commit

            Important notes:
            - NEVER run additional commands to read or explore code, besides git commands
            - DO NOT push to the remote repository unless the user explicitly asks you to do so
            - IMPORTANT: Never use git commands with the -i flag (like git rebase -i or git add -i) since they require interactive input which is not supported.
            - IMPORTANT: Do not use --no-edit with git rebase commands, as the --no-edit flag is not a valid option for git rebase.
            - If there are no changes to commit (i.e., no untracked files and no modifications), do not create an empty commit
            - In order to ensure good formatting, ALWAYS pass the commit message via a here-string, a la this example:
            <example>
            git commit -m @'
            Commit message here.

            Co-Authored-By: core-ai-cli <noreply@chancetop.com>
            '@
            </example>

            # Creating pull requests
            Use the gh command via the ${tool_shell} tool for ALL GitHub-related tasks including working with issues, pull requests, checks, and releases. If given a Github URL use the gh command to get the information needed.

            IMPORTANT: When the user asks you to create a pull request, follow these steps carefully:

            1. Run the following commands in parallel using the ${tool_shell} tool, in order to understand the current state of the branch since it diverged from the main branch:
               - Run a git status command to see all untracked files (never use -uall flag)
               - Run a git diff command to see both staged and unstaged changes that will be committed
               - Check if the current branch tracks a remote branch and is up to date with the remote, so you know if you need to push to the remote
               - Run a git log command and `git diff [base-branch]...HEAD` to understand the full commit history for the current branch (from the time it diverged from the base branch)
            2. Analyze all changes that will be included in the pull request, making sure to look at all relevant commits (NOT just the latest commit, but ALL commits that will be included in the pull request!!!), and draft a pull request title and summary:
               - Keep the PR title short (under 70 characters)
               - Use the description/body for details, not the title
            3. Run the following commands:
               - Create new branch if needed
               - Push to remote with -u flag if needed
               - Create PR using gh pr create with the format below. Use a here-string to pass the body to ensure correct formatting.
            <example>
            gh pr create --title "the pr title" --body @'
            ## Summary
            <1-3 bullet points>

            ## Test plan
            [Bulleted markdown checklist of TODOs for testing the pull request...]

            🤖 Generated with core-ai-cli
            '@
            </example>

            Important:
            - Return the PR URL when you're done, so the user can see it

            # Other common operations
            - View comments on a Github PR: gh api repos/foo/bar/pulls/123/comments
            """;

    private static final String TOOL_DESC = buildToolDescription();

    private static String buildToolDescription() {
        return TOOL_DESC_TEMPLATE
                .replace("${tool_read_file}", ReadFileTool.TOOL_NAME)
                .replace("${tool_edit_file}", EditFileTool.TOOL_NAME)
                .replace("${tool_glob}", GlobFileTool.TOOL_NAME)
                .replace("${tool_grep}", GrepFileTool.TOOL_NAME)
                .replace("${tool_write_file}", WriteFileTool.TOOL_NAME)
                .replace("${tool_shell}", POWERSHELL_TOOL_NAME)
                .replace("${default_timeout_ms}", String.valueOf(DEFAULT_TIMEOUT_MILLISECONDS))
                .replace("${default_timeout_minutes}", String.valueOf(DEFAULT_TIMEOUT_MILLISECONDS / 60000))
                .replace("${os}", System.getProperty("os.name"));
    }

    public static PowershellBuilder builder() {
        return new PowershellBuilder();
    }
    public static class PowershellBuilder extends Builder {
        @Override
        protected PowershellBuilder self() {
            return this;
        }

        @Override
        public PowershellCommandTool build() {
            this.name(POWERSHELL_TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "workspace", "Working directory for command execution"),
                    ToolCallParameters.ParamSpec.of(Integer.class, "timeout", "Optional timeout in milliseconds (max 600000)"),
                    ToolCallParameters.ParamSpec.of(String.class, "command", "The PowerShell command to execute").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "description", "Clear, concise description of what this command does in active voice."),
                    ToolCallParameters.ParamSpec.of(String.class, "mode", "Operation mode: 'read' for read-only operations, 'write' for modifications.").required(),
                    ToolCallParameters.ParamSpec.of(Boolean.class, "run_in_background", "Set to true to run this command in the background.")
            ));
            var tool = new PowershellCommandTool();
            build(tool);
            return tool;
        }
    }
}
