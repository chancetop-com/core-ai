package ai.core.cli.appserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class SessionTitleStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void setAndLoadAcrossInstances() {
        var store = new SessionTitleStore(tempDir);
        assertTrue(store.title("s1").isEmpty());
        store.set("s1", "Fix gradle build");
        assertEquals("Fix gradle build", store.title("s1").orElseThrow());

        var reloaded = new SessionTitleStore(tempDir);
        assertEquals("Fix gradle build", reloaded.title("s1").orElseThrow());
    }

    @Test
    void blankTitleRemovesEntry() {
        var store = new SessionTitleStore(tempDir);
        store.set("s1", "name");
        store.set("s1", "  ");
        assertTrue(store.title("s1").isEmpty());
    }

    @Test
    void removePersists() {
        var store = new SessionTitleStore(tempDir);
        store.set("s1", "name");
        store.remove("s1");
        assertTrue(new SessionTitleStore(tempDir).title("s1").isEmpty());
    }
}
