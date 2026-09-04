package ai.core.cli.hub;

import java.util.Locale;

/**
 * Paste-ready guide for shell-capable agents (Claude Code, Codex, CI scripts) to
 * discover and use company tools and skills through {@code core-ai-cli}. Both
 * {@code mcp instructions} and {@code skill instructions} print the same merged
 * document; {@code --format} only changes the heading style.
 *
 * @author stephen
 */
public final class HubInstructions {
    private static final String BODY = """
            Tools:  core-ai-cli mcp search "<what you need>" --json
                    core-ai-cli mcp describe <server>/<tool> --json      # read input_schema before calling
                    core-ai-cli mcp call <server>/<tool> --args '<json>' --json
            Do NOT guess tool names.
            Skills: when a task matches a known workflow, first check for a skill:
              1. Discover: core-ai-cli skill search "<topic>" --json
              2. Read:     core-ai-cli skill show <namespace>/<name> --raw   # prints SKILL.md; follow its instructions
              3. Optional: core-ai-cli skill pull <namespace>/<name> --to .claude/skills   # install for reuse
            Exit code 0 = success; parse stdout as JSON. On 4 (permission) stop and tell the user.
            """;

    public static String heading(String format) {
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "claude" -> "## Company tools & skills (core-ai Hub)  # paste into CLAUDE.md";
            case "codex" -> "## Company tools & skills (core-ai Hub)  # paste into AGENTS.md";
            default -> "## Company tools & skills (core-ai Hub)";
        };
    }

    public static String merged(String format) {
        return heading(format) + "\n" + BODY;
    }

    private HubInstructions() {
    }
}
