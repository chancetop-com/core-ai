package ai.core.server.dataset;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Static helpers shared by every store: filter/data parsing and the RFC 7386 merge used by the stores
 * that cannot delegate the merge to the database.
 *
 * @author stephen
 */
class DatasetRecordServiceTest {
    @Test
    void parseFilterTreatsBlankAndNullTextAsNoFilter() {
        assertNull(DatasetRecordService.parseFilter(null));
        assertNull(DatasetRecordService.parseFilter(" "));
        assertNull(DatasetRecordService.parseFilter("null"));
        assertEquals(Map.of("status", "done"), DatasetRecordService.parseFilter("{\"status\":\"done\"}"));
    }

    @Test
    void parseFilterRejectsNonObjectJson() {
        assertThrows(IllegalArgumentException.class, () -> DatasetRecordService.parseFilter("[1,2]"));
    }

    @Test
    void parseDataRejectsEmptyAndNonObjectJson() {
        assertThrows(IllegalArgumentException.class, () -> DatasetRecordService.parseData("{}"));
        assertThrows(IllegalArgumentException.class, () -> DatasetRecordService.parseData("[1]"));
        assertThrows(IllegalArgumentException.class, () -> DatasetRecordService.parseData(null));
    }

    @Test
    void mergeRecursivelyMergesObjects() {
        var current = Map.<String, Object>of(
                "business_type", "QSR",
                "raw_menu", Map.of("restaurantInfo", Map.of("name", "Old"), "categories", "keep"));
        var patch = Map.<String, Object>of(
                "raw_menu", Map.of("restaurantInfo", Map.of("name", "New")));

        var merged = DatasetRecordService.merge(current, patch);

        assertEquals("QSR", merged.get("business_type"));
        @SuppressWarnings("unchecked")
        var rawMenu = (Map<String, Object>) merged.get("raw_menu");
        assertEquals("keep", rawMenu.get("categories"));
        @SuppressWarnings("unchecked")
        var restaurantInfo = (Map<String, Object>) rawMenu.get("restaurantInfo");
        assertEquals("New", restaurantInfo.get("name"));
    }

    @Test
    void mergeReplacesArraysAndScalars() {
        var current = Map.<String, Object>of(
                "categories", "old-array",
                "menu_source", "OFFLINE");
        var patch = Map.<String, Object>of(
                "categories", "new-array",
                "menu_source", "ONLINE");

        var merged = DatasetRecordService.merge(current, patch);

        assertEquals("new-array", merged.get("categories"));
        assertEquals("ONLINE", merged.get("menu_source"));
    }

    @Test
    void mergeNullDeletesField() {
        var current = Map.<String, Object>of("menu_source", "ONLINE", "keep", "value");
        var patch = new HashMap<String, Object>();
        patch.put("menu_source", null);

        var merged = DatasetRecordService.merge(current, patch);

        assertEquals(1, merged.size());
        assertEquals("value", merged.get("keep"));
    }

    @Test
    void mergeReplacesNonObjectTargetWithPatchedObjectFollowingRfc7386() {
        var current = Map.<String, Object>of("meta", 5);
        var patch = Map.<String, Object>of("meta", Map.of("keep", 1));

        var merged = DatasetRecordService.merge(current, patch);

        assertEquals(Map.of("keep", 1), merged.get("meta"));
    }

    @Test
    void mergeDropsNullsInsideAnObjectPatchThatReplacesAScalar() {
        var current = Map.<String, Object>of("meta", 5);
        var patch = new HashMap<String, Object>();
        var nested = new HashMap<String, Object>();
        nested.put("keep", 1);
        nested.put("drop", null);
        patch.put("meta", nested);

        var merged = DatasetRecordService.merge(current, patch);

        @SuppressWarnings("unchecked")
        var meta = (Map<String, Object>) merged.get("meta");
        assertEquals(1, meta.size());
        assertEquals(1, meta.get("keep"));
    }
}
