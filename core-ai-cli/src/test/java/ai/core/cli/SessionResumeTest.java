package ai.core.cli;

import ai.core.session.FileSessionPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An explicit {@code --resume <session-id>} is accepted only when the id is a safe file key and the
 * snapshot is readable, so a typo never starts a turn under the requested id.
 *
 * @author stephen
 */
class SessionResumeTest {
    private static final String INTACT = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}],\"history\":[],\"status\":\"COMPLETED\"}";

    @TempDir
    Path sessionsDir;

    @Test
    void acceptsAPersistedSession() {
        var persistence = new FileSessionPersistence(sessionsDir.toString());
        persistence.save("cli-20261030-164801", INTACT);

        var resumed = SessionResume.requireReadable(persistence, "cli-20261030-164801");

        assertEquals("cli-20261030-164801", resumed);
    }

    @Test
    void reportsAMissingSession() {
        var persistence = new FileSessionPersistence(sessionsDir.toString());

        var error = assertThrows(SessionResumeException.class,
                () -> SessionResume.requireReadable(persistence, "cli-20261030-000000"));

        assertTrue(error.getMessage().contains("not found"), error.getMessage());
        assertTrue(error.getMessage().contains("cli-20261030-000000"), error.getMessage());
    }

    @Test
    void reportsACorruptSession() throws Exception {
        var persistence = new FileSessionPersistence(sessionsDir.toString());
        Files.writeString(sessionsDir.resolve("torn.data"), INTACT.substring(0, INTACT.length() / 2));

        var error = assertThrows(SessionResumeException.class,
                () -> SessionResume.requireReadable(persistence, "torn"));

        assertTrue(error.getMessage().contains("corrupted"), error.getMessage());
    }

    @Test
    void reportsASnapshotFromANewerVersion() {
        var persistence = new FileSessionPersistence(sessionsDir.toString());
        persistence.save("newer", "{\"version\":99,\"messages\":[],\"history\":[],\"status\":\"COMPLETED\"}");

        var error = assertThrows(SessionResumeException.class,
                () -> SessionResume.requireReadable(persistence, "newer"));

        assertTrue(error.getMessage().contains("newer core-ai"), error.getMessage());
        assertTrue(error.getMessage().contains("99"), error.getMessage());
    }

    @Test
    void rejectsAnIdThatCannotBeASessionFile() {
        var persistence = new FileSessionPersistence(sessionsDir.toString());

        var error = assertThrows(SessionResumeException.class,
                () -> SessionResume.requireReadable(persistence, "../escape"));

        assertTrue(error.getMessage().contains("Invalid session id"), error.getMessage());
        assertFalse(Files.exists(sessionsDir.getParent().resolve("escape.data")));
    }

    @Test
    void rejectsAnIdOutsideTheSessionIdCharset() {
        var persistence = new FileSessionPersistence(sessionsDir.toString());

        var error = assertThrows(SessionResumeException.class,
                () -> SessionResume.requireReadable(persistence, "cli:20261030"));

        assertTrue(error.getMessage().contains("Invalid session id"), error.getMessage());
    }
}
