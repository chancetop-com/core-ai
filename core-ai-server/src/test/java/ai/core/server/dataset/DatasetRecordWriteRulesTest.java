package ai.core.server.dataset;

import ai.core.server.domain.Dataset;
import ai.core.server.domain.SchemaField;
import ai.core.server.domain.SchemaFieldType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Type coercion at the write boundary keeps the generated views from silently turning mismatched values into
 * NULL, and preserves null as the merge-patch delete marker.
 *
 * @author stephen
 */
class DatasetRecordWriteRulesTest {
    private static SchemaField field(String name, SchemaFieldType type) {
        var field = new SchemaField();
        field.name = name;
        field.type = type;
        return field;
    }

    private static Dataset dataset(SchemaField... fields) {
        var dataset = new Dataset();
        dataset.id = "ds-1";
        dataset.name = "test";
        dataset.schema = List.of(fields);
        return dataset;
    }

    private static Map<String, Object> data(Object... values) {
        var data = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) data.put((String) values[i], values[i + 1]);
        return data;
    }

    @Test
    void coercesScalarsToTheDeclaredTypes() {
        var dataset = dataset(
                field("count", SchemaFieldType.NUMBER),
                field("label", SchemaFieldType.STRING),
                field("active", SchemaFieldType.BOOLEAN),
                field("day", SchemaFieldType.DATE),
                field("at", SchemaFieldType.DATETIME));

        var normalized = DatasetRecordWriteRules.normalize(dataset, data(
                "count", "42",
                "label", 42,
                "active", "TRUE",
                "day", "2026-11-06T15:30:00Z",
                "at", "2026-11-06T12:00:00+08:00"));

        assertEquals(new BigDecimal("42"), normalized.get("count"));
        assertEquals("42", normalized.get("label"));
        assertEquals(Boolean.TRUE, normalized.get("active"));
        assertEquals("2026-11-06", normalized.get("day"));
        assertEquals("2026-11-06T12:00+08:00", normalized.get("at"));
    }

    @Test
    void datetimeWithoutOffsetUsesUtc() {
        var dataset = dataset(field("at", SchemaFieldType.DATETIME));
        assertEquals("2026-11-06T12:00Z", DatasetRecordWriteRules.normalize(dataset, data("at", "2026-11-06T12:00")).get("at"));
    }

    @Test
    void rejectsValuesThatCannotBeConverted() {
        var dataset = dataset(field("count", SchemaFieldType.NUMBER), field("day", SchemaFieldType.DATE));

        var numberError = assertThrows(IllegalArgumentException.class,
                () -> DatasetRecordWriteRules.normalize(dataset, data("count", "not-a-number")));
        assertTrue(numberError.getMessage().contains("count"));
        assertTrue(numberError.getMessage().contains("NUMBER"));

        var dateError = assertThrows(IllegalArgumentException.class,
                () -> DatasetRecordWriteRules.normalize(dataset, data("day", "not-a-date")));
        assertTrue(dateError.getMessage().contains("day"));
        assertTrue(dateError.getMessage().contains("DATE"));
    }

    @Test
    void patchKeepsNullAsDeleteButInsertDropsIt() {
        var dataset = dataset(field("label", SchemaFieldType.STRING));
        var insert = DatasetRecordWriteRules.normalize(dataset, data("label", null));
        assertFalse(insert.containsKey("label"));

        var patch = DatasetRecordWriteRules.normalizePatch(dataset, data("label", null));
        assertTrue(patch.containsKey("label"));
        assertNull(patch.get("label"));
    }

    @Test
    void schemaLessDatasetAcceptsEveryField() {
        var dataset = new Dataset();
        dataset.id = "ds-1";
        var normalized = DatasetRecordWriteRules.normalize(dataset, data("anything", "value", "nested", Map.of("a", 1)));
        assertEquals("value", normalized.get("anything"));
        assertTrue(normalized.containsKey("nested"));
    }

    @Test
    void dropsFieldsOutsideTheSchema() {
        var dataset = dataset(field("label", SchemaFieldType.STRING));
        var normalized = DatasetRecordWriteRules.normalize(dataset, data("label", "x", "unknown", "y"));
        assertEquals(Map.of("label", "x"), normalized);
    }
}
