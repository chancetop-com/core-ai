package ai.core.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class IdMappingUtilTest {
    @Test
    void fromItemsStringId() {
        var items = List.of(new Item("uuid-aaa", "A"), new Item("uuid-bbb", "B"), new Item("uuid-ccc", "C"));
        var mapping = IdMappingUtil.from(items, i -> i.id);

        assertEquals("1", mapping.toSimple("uuid-aaa"));
        assertEquals("2", mapping.toSimple("uuid-bbb"));
        assertEquals("3", mapping.toSimple("uuid-ccc"));

        assertEquals("uuid-aaa", mapping.toOriginal("1"));
        assertEquals("uuid-bbb", mapping.toOriginal("2"));
        assertEquals("uuid-ccc", mapping.toOriginal("3"));
    }

    @Test
    void fromIdsCollection() {
        var mapping = IdMappingUtil.fromIds(List.of("id-x", "id-y"));

        assertEquals("1", mapping.toSimple("id-x"));
        assertEquals("2", mapping.toSimple("id-y"));
        assertEquals("id-x", mapping.toOriginal("1"));
    }

    @Test
    void fromIdsLongType() {
        var mapping = IdMappingUtil.fromIds(List.of(100L, 200L, 300L));

        assertEquals("1", mapping.toSimple(100L));
        assertEquals("2", mapping.toSimple(200L));
        assertEquals(200L, mapping.toOriginal("2"));
    }

    @Test
    void customIdGenerator() {
        var items = List.of(new Item("uuid-aaa", "A"), new Item("uuid-bbb", "B"));
        var mapping = IdMappingUtil.from(items, i -> i.id, i -> "t" + i);

        assertEquals("t1", mapping.toSimple("uuid-aaa"));
        assertEquals("t2", mapping.toSimple("uuid-bbb"));
        assertEquals("uuid-aaa", mapping.toOriginal("t1"));
    }

    @Test
    void simplifyIdsMutableObjects() {
        var items = new ArrayList<>(List.of(new Item("uuid-aaa", "A"), new Item("uuid-bbb", "B")));
        var mapping = IdMappingUtil.from(items, i -> i.id);

        mapping.simplifyIds(items, i -> i.id, (i, id) -> i.id = id);
        assertEquals("1", items.get(0).id);
        assertEquals("2", items.get(1).id);

        mapping.restoreIds(items, i -> i.id, (i, id) -> i.id = id);
        assertEquals("uuid-aaa", items.get(0).id);
        assertEquals("uuid-bbb", items.get(1).id);
    }

    @Test
    void simplifyAllImmutableObjects() {
        var items = List.of(new Entry("uuid-aaa", "val-A"), new Entry("uuid-bbb", "val-B"));
        var mapping = IdMappingUtil.from(items, Entry::id);

        var simplified = mapping.simplifyAll(items, Entry::id, Entry::withId);
        assertEquals("1", simplified.get(0).id());
        assertEquals("val-A", simplified.get(0).value());
        assertEquals("2", simplified.get(1).id());

        var restored = mapping.restoreAll(simplified, Entry::id, Entry::withId);
        assertEquals("uuid-aaa", restored.get(0).id());
        assertEquals("uuid-bbb", restored.get(1).id());
    }

    @Test
    void duplicateIdsDeduped() {
        var items = List.of(new Item("same-id", "A"), new Item("same-id", "B"), new Item("other-id", "C"));
        var mapping = IdMappingUtil.from(items, i -> i.id);

        assertEquals("1", mapping.toSimple("same-id"));
        assertEquals("2", mapping.toSimple("other-id"));
    }

    @Test
    void nullIdsSkipped() {
        var items = Arrays.asList("id-a", null, "id-b");
        var mapping = IdMappingUtil.fromIds(items);

        assertEquals("1", mapping.toSimple("id-a"));
        assertEquals("2", mapping.toSimple("id-b"));
        assertNull(mapping.toOriginal("3"));
    }

    @Test
    void toSimpleUnknownIdFallbackToString() {
        var mapping = IdMappingUtil.fromIds(List.of("id-a"));
        assertEquals("unknown", mapping.toSimple("unknown"));
    }

    @Test
    void toOriginalUnknownIdReturnsNull() {
        var mapping = IdMappingUtil.fromIds(List.of("id-a"));
        assertNull(mapping.toOriginal("999"));
    }

    @Test
    void simplifyIdsNullListNoException() {
        var mapping = IdMappingUtil.fromIds(List.of("id-a"));
        assertDoesNotThrow(() -> mapping.simplifyIds(null, Object::toString, (i, id) -> { }));
    }

    @Test
    void simplifyAllNullListReturnsEmpty() {
        var mapping = IdMappingUtil.fromIds(List.of("id-a"));
        var result = mapping.simplifyAll(null, Object::toString, (i, id) -> i);
        assertTrue(result.isEmpty());
    }

    static class Item {
        String id;
        String name;

        Item(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    record Entry(String id, String value) {
        Entry withId(String newId) {
            return new Entry(newId, value);
        }
    }
}
