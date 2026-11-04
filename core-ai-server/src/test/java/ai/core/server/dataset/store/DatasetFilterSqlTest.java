package ai.core.server.dataset.store;

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
 * @author stephen
 */
class DatasetFilterSqlTest {
    @Test
    void emptyFilterHasNoClause() {
        var clause = DatasetFilterSql.build(Map.of());
        assertNull(clause.sql());
        assertTrue(clause.params().isEmpty());
    }

    @Test
    void scalarFilterUsesContainmentWithNestedObjectParameter() {
        var clause = DatasetFilterSql.build(Map.of("meta.priority", 3));

        assertEquals("data @> ?::jsonb", clause.sql());
        assertEquals(List.of("{\"meta\":{\"priority\":3}}"), clause.params());
    }

    @Test
    void userInputNeverReachesTheSqlText() {
        var filter = new LinkedHashMap<String, Object>();
        filter.put("a'b\"c; DROP TABLE dataset_records; --", "x'; DELETE FROM dataset_records; --");
        var clause = DatasetFilterSql.build(filter);

        assertFalse(clause.sql().contains("DROP"));
        assertFalse(clause.sql().contains("DELETE"));
        assertFalse(clause.sql().contains("'"));
        assertTrue(clause.sql().contains("?::jsonb"));
        assertEquals(1, clause.params().size());
        assertTrue(String.valueOf(clause.params().getFirst()).contains("DROP TABLE"));
    }

    @Test
    void nullFilterChecksMissingAndJsonNull() {
        var filter = new LinkedHashMap<String, Object>();
        filter.put("status", null);
        var clause = DatasetFilterSql.build(filter);

        assertTrue(clause.sql().contains("IS NULL"));
        assertEquals(List.of("status", "status", "null"), clause.params());
    }

    @Test
    void objectAndArrayValuesUseDeepEquality() {
        assertEquals("data #> string_to_array(?, chr(46)) = ?::jsonb",
                DatasetFilterSql.build(Map.of("meta", Map.of("a", 1))).sql());
        assertEquals("data #> string_to_array(?, chr(46)) = ?::jsonb",
                DatasetFilterSql.build(Map.of("tags", List.of("a", "b"))).sql());
    }

    @Test
    void comparisonOperatorsGuardTheTypeCast() {
        var clause = DatasetFilterSql.build(Map.of("score", Map.of("$gt", 10)));

        assertTrue(clause.sql().contains("CASE WHEN jsonb_typeof"));
        assertEquals(List.of("score", "number", "score", BigDecimal.TEN), clause.params());
    }

    @Test
    void inListIsExpandedFromAJsonParameter() {
        var clause = DatasetFilterSql.build(Map.of("status", Map.of("$in", List.of("a", "b"))));
        assertEquals(List.of("status", "[\"a\",\"b\"]"), clause.params());
        assertFalse(clause.sql().contains("\"a\""));
    }

    @Test
    void containsEscapesLikeWildcards() {
        var clause = DatasetFilterSql.build(Map.of("name", Map.of("$contains", "50%_off")));
        assertEquals("%50\\%\\_off%", clause.params().get(1));
        assertTrue(clause.sql().contains("ILIKE ?"));
    }

    @Test
    void unknownOperatorIsRejected() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> DatasetFilterSql.build(Map.of("score", Map.of("$regex", "x"))));
        assertTrue(error.getMessage().contains("$eq"));
    }
}
