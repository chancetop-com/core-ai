package ai.core.cli.appserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class SessionModelStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void setAndLoadAcrossInstances() {
        var store = new SessionModelStore(tempDir);
        assertTrue(store.model("s1").isEmpty());
        store.set("s1", "model-b", "litellm");

        var entry = new SessionModelStore(tempDir).model("s1").orElseThrow();
        assertEquals("model-b", entry.model);
        assertEquals("litellm", entry.provider);
    }

    @Test
    void removePersists() {
        var store = new SessionModelStore(tempDir);
        store.set("s1", "model-b", "openai");
        store.remove("s1");
        assertTrue(new SessionModelStore(tempDir).model("s1").isEmpty());
    }

    @Test
    void corruptFileFallsBackToEmpty() throws Exception {
        Files.writeString(tempDir.resolve("models.json"), "not json");

        var store = new SessionModelStore(tempDir);
        assertTrue(store.model("s1").isEmpty());
        store.set("s1", "model-b", "openai");
        assertEquals("model-b", new SessionModelStore(tempDir).model("s1").orElseThrow().model);
    }
}
