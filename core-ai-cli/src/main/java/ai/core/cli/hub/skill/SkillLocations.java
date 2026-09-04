package ai.core.cli.hub.skill;

import java.nio.file.Path;

/**
 * Standard local install roots for hub-managed skills. The user root mirrors what
 * {@code CliAgent} loads at user priority; the workspace root is what
 * {@code pull --workspace} targets and {@code CliAgent} loads at workspace priority.
 *
 * @author stephen
 */
public final class SkillLocations {
    public static Path userSkillsDir() {
        return Path.of(System.getProperty("user.home"), ".core-ai", "skills");
    }

    public static Path workspaceSkillsDir() {
        return Path.of(System.getProperty("user.dir"), ".core-ai", "skills");
    }

    private SkillLocations() {
    }
}
