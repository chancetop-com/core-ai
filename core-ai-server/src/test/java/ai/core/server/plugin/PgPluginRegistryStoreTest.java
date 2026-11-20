package ai.core.server.plugin;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgPluginRegistryStoreTest {
    @Test
    void searchBindsEachColumnToItsOwnPattern() {
        var params = new ArrayList<>();
        var where = new PgPluginRegistryStore().where("hello", params);

        assertEquals(" WHERE data ->> ? ILIKE ? OR data ->> ? ILIKE ? OR data ->> ? ILIKE ? OR id ILIKE ?", where);
        assertEquals(List.of("name", "%hello%", "description", "%hello%", "vendor", "%hello%", "%hello%"), params);
    }

    @Test
    void aBlankQueryAddsNoConditionOrParams() {
        var params = new ArrayList<>();
        assertTrue(new PgPluginRegistryStore().where("  ", params).isEmpty());
        assertTrue(params.isEmpty());
    }
}
