package ai.core.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * @author stephen
 */
class SessionLoadsTest {
    private static final String INTACT = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}],\"history\":[],\"status\":\"COMPLETED\"}";

    @Test
    void classifiesEverySnapshotOutcome(@TempDir Path dir) throws Exception {
        var persistence = new FileSessionPersistence(dir.toString());
        persistence.save("good", INTACT);
        assertInstanceOf(SessionLoad.Ok.class, SessionLoads.load(persistence, "good"));
        assertEquals("hello", SessionLoads.title(persistence, "good").text());

        persistence.save("blank", "");
        assertEquals(SessionLoadState.CORRUPT, SessionLoads.title(persistence, "blank").state());

        Files.writeString(dir.resolve("torn.data"), INTACT.substring(0, INTACT.length() / 2));
        assertEquals(SessionLoadState.CORRUPT, SessionLoads.title(persistence, "torn").state());

        persistence.save("newer", "{\"version\":99,\"messages\":[],\"history\":[],\"status\":\"COMPLETED\"}");
        var newer = SessionLoads.load(persistence, "newer");
        assertInstanceOf(SessionLoad.NewerVersion.class, newer);
        assertEquals(99, ((SessionLoad.NewerVersion) newer).version());

        var missing = SessionLoads.title(persistence, "missing");
        assertEquals(SessionLoadState.MISSING, missing.state());
    }
}
