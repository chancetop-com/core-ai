package ai.core.server.runtime;


import ai.core.server.testsupport.RecordingDatabase;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author stephen
 */
class PgRuntimeSampleSqlParamsTest {
    @Test
    void statements() {
        var database = new RecordingDatabase();
        var store = new PgRuntimeSampleStore();
        store.database = database;

        var sample = new RuntimeSample();
        sample.id = "s1";
        store.insert(sample);
        store.bulkInsert(List.of(sample, sample));
        store.range(ZonedDateTime.parse("2026-11-01T00:00:00Z"), ZonedDateTime.parse("2026-11-02T00:00:00Z"), 10);

        assertEquals(3, database.statementCount());
    }
}
