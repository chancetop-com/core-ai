package ai.core.server.workspace;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Default workspace documentation and note templates seeded once on first volume initialization.
 * Existing files on the volume are never overwritten.
 *
 * @author stephen
 */
public final class WorkspaceTemplates {
    public static final String AGENTS_MD_PATH = WorkspaceDefaults.MOUNT_PATH + "/AGENTS.md";
    public static final String NOTES_MD_PATH = WorkspaceDefaults.MOUNT_PATH + "/.core-ai/NOTES.md";

    public static final String AGENTS_MD_CONTENT = """
            # Workspace Guide (AGENTS.md)

            This directory (`/workspace`) is a persistent volume attached across sessions.

            ## Directory Layout
            - `/workspace` — project files, repositories, and build artifacts (persisted)
            - `/workspace/home` — persistent `$HOME` (caches, tool configs)
            - `/workspace/.core-ai/env` — persistent environment variables (`KEY=VALUE`)
            - `/workspace/.core-ai/profile.sh` — optional bash profile sourced before bash commands
            - `/workspace/.core-ai/NOTES.md` — running workspace notes across sessions
            - `/workspace/.core-ai/logs/` — bootstrap, start, and profile logs (last 20 retained)

            ## Environment Variables (`/workspace/.core-ai/env`)
            - One `KEY=VALUE` per line; literal values after `=` (no shell expansion, no quotes, no multiline).
            - Use `PATH_PREPEND=/dir1:/dir2` to prepend directories to `PATH` (do not set `PATH` directly).
            - Changes take effect on the next `run_bash_command` or `run_python_script` call.

            ## Testing & Verification
            - Document your project's build, lint, and test entrypoints here so future sessions run them consistently.

            ## Prohibitions
            - Never store raw secrets, API tokens, or credentials inside `/workspace`.
            - Do not override protected runtime keys (`HOME`, `WORKSPACE_DIR`, `PATH`, `CORE_AI_*`, `LD_PRELOAD`, `LD_LIBRARY_PATH`, `SHELL`, `PORT`, `HUB_PORT`, `MAX_ASYNC_TASKS`).
            """;

    public static final String NOTES_MD_CONTENT = """
            # Workspace Notes

            """;

    public static String buildEnsureCommand() {
        var agentsB64 = Base64.getEncoder().encodeToString(AGENTS_MD_CONTENT.getBytes(StandardCharsets.UTF_8));
        var notesB64 = Base64.getEncoder().encodeToString(NOTES_MD_CONTENT.getBytes(StandardCharsets.UTF_8));
        return "mkdir -p /workspace/.core-ai /workspace/.core-ai/logs /workspace/home\n"
                + "if [ ! -e " + AGENTS_MD_PATH + " ]; then printf '%s' '" + agentsB64 + "' | base64 -d > " + AGENTS_MD_PATH + "; fi\n"
                + "if [ ! -e " + NOTES_MD_PATH + " ]; then printf '%s' '" + notesB64 + "' | base64 -d > " + NOTES_MD_PATH + "; fi\n";
    }

    private WorkspaceTemplates() {
    }
}
