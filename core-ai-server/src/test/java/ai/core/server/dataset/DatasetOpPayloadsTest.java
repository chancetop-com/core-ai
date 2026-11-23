package ai.core.server.dataset;

import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A write that stores fewer fields than the caller sent has to say so; otherwise a typo in a column name looks like
 * a successful load and the missing column is only noticed when a report comes back wrong.
 */
class DatasetOpPayloadsTest {
    @Test
    void insertedPayloadListsTheDroppedFields() {
        var payload = JsonUtil.toMap(DatasetOpPayloads.recordInserted("d1", List.of("label"), List.of("lable")));

        assertEquals(List.of("lable"), payload.get("dropped_fields"));
        assertEquals(List.of("label"), payload.get("inserted_fields"));
    }

    @Test
    void payloadWithoutDroppedFieldsKeepsTheOriginalShape() {
        Map<String, Object> payload = JsonUtil.toMap(DatasetOpPayloads.recordInserted("d1", List.of("label")));

        assertFalse(payload.containsKey("dropped_fields"));
        assertEquals("created", payload.get("status"));
    }

    @Test
    void updatedPayloadListsTheDroppedFields() {
        var payload = JsonUtil.toMap(DatasetOpPayloads.recordUpdated("r1", "d1", List.of("label"), List.of("lable")));

        assertEquals(List.of("lable"), payload.get("dropped_fields"));
        assertEquals("updated", payload.get("status"));
    }
}
