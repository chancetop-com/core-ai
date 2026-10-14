package ai.core.session;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Subagent thread files live next to their session under the {@code ~sub~} namespace: they never show
 * up as sessions, and deleting a session takes its threads with it.
 *
 * @author stephen
 */
class FileSessionPersistenceTest {

    @Test
    void subagentFilesAreHiddenFromListingsAndDeletedWithTheirSession() throws Exception {
        Path dir = Files.createTempDirectory("session-persistence-test");
        try {
            var persistence = new FileSessionPersistence(dir.toString());
            persistence.save("s1", "{\"messages\":[]}");
            persistence.save("s1~sub~sa-1", "{}");
            persistence.save("s1~sub~_index", "{}");
            persistence.save("s2", "{}");

            var ids = persistence.listSessions().stream().map(SessionPersistence.SessionInfo::id).sorted().toList();
            assertEquals(List.of("s1", "s2"), ids, "thread files must not be listed as sessions");

            persistence.delete(List.of("s1"));

            assertFalse(Files.exists(dir.resolve("s1.data")));
            assertFalse(Files.exists(dir.resolve("s1~sub~sa-1.data")), "thread data must go with the session");
            assertFalse(Files.exists(dir.resolve("s1~sub~_index.data")), "the thread index must go with the session");
            assertTrue(Files.exists(dir.resolve("s2.data")), "other sessions must be untouched");
        } finally {
            core.framework.util.Files.deleteDir(dir);
        }
    }
}
