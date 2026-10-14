package ai.core.cli.helper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * File round-trip of the user-level helper configuration.
 *
 * @author stephen
 */
class HelperConfigStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsAcrossStoreInstances() {
        var file = tempDir.resolve("helpers.json");
        var store = new HelperConfigStore(file);
        store.add(entry("a1", "HR 助手"));

        var reloaded = new HelperConfigStore(file);

        assertEquals(1, reloaded.list().size());
        assertEquals("HR 助手", reloaded.find("a1").name);
    }

    @Test
    void updateSetsAndClearsHint() {
        var store = new HelperConfigStore(tempDir.resolve("helpers.json"));
        store.add(entry("a1", "HR 助手"));

        assertTrue(store.update("a1", "休假先问它", null));
        assertEquals("休假先问它", store.find("a1").whenToUse);

        assertTrue(store.update("a1", "", null));
        assertNull(store.find("a1").whenToUse);
    }

    @Test
    void updateAndRemoveUnknownReturnFalse() {
        var store = new HelperConfigStore(tempDir.resolve("helpers.json"));

        assertFalse(store.update("missing", "x", null));
        assertFalse(store.remove("missing"));
    }

    @Test
    void removeDeletesEntry() {
        var store = new HelperConfigStore(tempDir.resolve("helpers.json"));
        store.add(entry("a1", "HR 助手"));

        assertTrue(store.remove("a1"));
        assertTrue(store.list().isEmpty());
    }

    private HelperEntry entry(String agentId, String name) {
        var entry = new HelperEntry();
        entry.agentId = agentId;
        entry.name = name;
        entry.enabled = Boolean.TRUE;
        entry.addedAt = "2026-10-14T00:00:00Z";
        return entry;
    }
}
