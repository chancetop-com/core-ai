package ai.core.server.dataset.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryDatasetRecordsToolTest {
    @Test
    void parseFilterReturnsNullForBlankOrNullLiteral() {
        assertNull(QueryDatasetRecordsTool.parseFilter(null));
        assertNull(QueryDatasetRecordsTool.parseFilter("   "));
        assertNull(QueryDatasetRecordsTool.parseFilter("null"));
        assertNull(QueryDatasetRecordsTool.parseFilter(" null "));
    }

    @Test
    void parseFilterParsesJsonObject() {
        assertEquals(Map.of("status", "done"), QueryDatasetRecordsTool.parseFilter("{\"status\":\"done\"}"));
    }

    @Test
    void parseFilterRejectsInvalidJson() {
        assertThrows(IllegalArgumentException.class, () -> QueryDatasetRecordsTool.parseFilter("not json"));
        assertThrows(IllegalArgumentException.class, () -> QueryDatasetRecordsTool.parseFilter("[1,2]"));
    }
}
