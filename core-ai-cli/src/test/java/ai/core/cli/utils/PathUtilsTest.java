package ai.core.cli.utils;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Session files are stored per workspace; two different workspaces must never share a session directory,
 * otherwise {@code --continue} resumes another project's conversation and the desktop lists it.
 *
 * @author stephen
 */
class PathUtilsTest {
    @Test
    void sessionsDirIsDistinctForDifferentWorkspacesWithTheSameName() {
        var first = PathUtils.sessionsDir(Path.of("D:/clients/acme/app"));
        var second = PathUtils.sessionsDir(Path.of("D:/clients/globex/app"));
        assertNotEquals(first, second, "both workspaces are named 'app' but are different projects");
    }
}
