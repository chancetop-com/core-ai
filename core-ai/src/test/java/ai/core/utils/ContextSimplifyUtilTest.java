package ai.core.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author stephen
 */
class ContextSimplifyUtilTest {
    @Test
    void simplifyIdsAndRestore() {
        var items = new ArrayList<>(List.of(new Item("uuid-aaa", "A", "img1"), new Item("uuid-bbb", "B", "img2")));

        var ctx = ContextSimplifyUtil.of(items)
                .simplifyIds(i -> i.id, (i, v) -> i.id = v);

        assertEquals("1", items.get(0).id);
        assertEquals("2", items.get(1).id);

        ctx.restore();
        assertEquals("uuid-aaa", items.get(0).id);
        assertEquals("uuid-bbb", items.get(1).id);
    }

    @Test
    void toSimpleAndToOriginal() {
        var items = new ArrayList<>(List.of(new Item("uuid-aaa", "A", "img1"), new Item("uuid-bbb", "B", "img2")));

        var ctx = ContextSimplifyUtil.of(items)
                .simplifyIds(i -> i.id, (i, v) -> i.id = v);

        assertEquals("1", ctx.toSimple("uuid-aaa"));
        assertEquals("2", ctx.toSimple("uuid-bbb"));
        assertEquals("unknown", ctx.toSimple("unknown"));

        assertEquals("uuid-aaa", ctx.toOriginal("1"));
        assertEquals("uuid-bbb", ctx.toOriginal("2"));
        assertNull(ctx.toOriginal("999"));

        ctx.restore();
    }

    @Test
    void nullifyAndRestore() {
        var items = new ArrayList<>(List.of(new Item("id1", "A", "img1"), new Item("id2", "B", "img2")));

        var ctx = ContextSimplifyUtil.of(items)
                .nullify(i -> i.imageUrl, (i, v) -> i.imageUrl = v);

        assertNull(items.get(0).imageUrl);
        assertNull(items.get(1).imageUrl);

        ctx.restore();
        assertEquals("img1", items.get(0).imageUrl);
        assertEquals("img2", items.get(1).imageUrl);
    }

    @Test
    void chainSimplifyAndNullify() {
        var items = new ArrayList<>(List.of(new Item("uuid-aaa", "A", "img1"), new Item("uuid-bbb", "B", null)));

        var ctx = ContextSimplifyUtil.of(items)
                .simplifyIds(i -> i.id, (i, v) -> i.id = v)
                .nullify(i -> i.imageUrl, (i, v) -> i.imageUrl = v);

        assertEquals("1", items.get(0).id);
        assertEquals("2", items.get(1).id);
        assertNull(items.get(0).imageUrl);
        assertNull(items.get(1).imageUrl);

        ctx.restore();
        assertEquals("uuid-aaa", items.get(0).id);
        assertEquals("uuid-bbb", items.get(1).id);
        assertEquals("img1", items.get(0).imageUrl);
        assertNull(items.get(1).imageUrl);
    }

    @Test
    void applyAutoRestores() {
        var items = new ArrayList<>(List.of(new Item("uuid-aaa", "A", "img1"), new Item("uuid-bbb", "B", "img2")));

        var result = ContextSimplifyUtil.of(items)
                .simplifyIds(i -> i.id, (i, v) -> i.id = v)
                .nullify(i -> i.imageUrl, (i, v) -> i.imageUrl = v)
                .apply(() -> items.get(0).id + "," + items.get(1).id);

        assertEquals("1,2", result);
        assertEquals("uuid-aaa", items.get(0).id);
        assertEquals("uuid-bbb", items.get(1).id);
        assertEquals("img1", items.get(0).imageUrl);
        assertEquals("img2", items.get(1).imageUrl);
    }

    @Test
    void applyRestoresOnException() {
        var items = new ArrayList<>(List.of(new Item("uuid-aaa", "A", "img1")));

        try {
            ContextSimplifyUtil.of(items)
                    .nullify(i -> i.imageUrl, (i, v) -> i.imageUrl = v)
                    .apply(() -> { throw new RuntimeException("fail"); });
        } catch (RuntimeException ignored) {
        }

        assertEquals("img1", items.get(0).imageUrl);
    }

    @Test
    void mapWithCustomMapper() {
        var items = new ArrayList<>(List.of(new Item("id1", "A", "img1")));

        var ctx = ContextSimplifyUtil.of(items)
                .map(i -> i.name, (i, v) -> i.name = v, v -> v.toLowerCase());

        assertEquals("a", items.get(0).name);

        ctx.restore();
        assertEquals("A", items.get(0).name);
    }

    @Test
    void emptyListNoError() {
        var ctx = ContextSimplifyUtil.of(List.<Item>of())
                .simplifyIds(i -> i.id, (i, v) -> i.id = v)
                .nullify(i -> i.imageUrl, (i, v) -> i.imageUrl = v);
        ctx.restore();
    }

    static class Item {
        String id;
        String name;
        String imageUrl;

        Item(String id, String name, String imageUrl) {
            this.id = id;
            this.name = name;
            this.imageUrl = imageUrl;
        }
    }
}
