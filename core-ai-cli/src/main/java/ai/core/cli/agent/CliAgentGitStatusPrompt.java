package ai.core.cli.agent;

import ai.core.prompt.PromptInject;
import ai.core.utils.ShellUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
record CliAgentGitStatusPrompt(Path workspace) implements PromptInject {
    @Override
    public SectionType type() {
        return SectionType.ENVIRONMENT;
    }

    @Override
    public String inject() {
        if (!Files.isDirectory(workspace.resolve(".git"))) {
            return "";
        }
        var sb = new StringBuilder(512);
        sb.append("<git_status>\n");
        appendBranch(sb);
        appendStatus(sb);
        appendRecentCommits(sb);
        sb.append("</git_status>");
        return sb.toString();
    }

    private void appendBranch(StringBuilder sb) {
        var branch = runGit("branch", "--show-current");
        if (!branch.isEmpty()) {
            sb.append("    Current branch: ").append(branch).append('\n');
        }
    }

    private void appendStatus(StringBuilder sb) {
        var status = runGit("status", "--short");
        if (status.isEmpty()) {
            sb.append("    Working tree: clean\n");
        } else {
            sb.append("    Uncommitted changes:\n");
            for (var line : status.lines().toList()) {
                sb.append("        ").append(line).append('\n');
            }
        }
    }

    private void appendRecentCommits(StringBuilder sb) {
        var log = runGit("log", "--oneline", "-5");
        if (!log.isEmpty()) {
            sb.append("    Recent commits:\n");
            for (var line : log.lines().toList()) {
                sb.append("        ").append(line).append('\n');
            }
        }
    }

    private String runGit(String... args) {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(args));
        return ShellUtil.execute(command, workspace);
    }
}
