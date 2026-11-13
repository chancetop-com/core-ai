package ai.core.server.runtime;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Storage for the per-minute runtime watermarks: append one sample per pod per tick, read back a time
 * window for the dashboard.
 *
 * @author stephen
 */
public interface RuntimeSampleStore {
    void insert(RuntimeSample sample);

    /**
     * @return how many rows were written; the backfill logs skipped rows when this is short of the page
     */
    int bulkInsert(List<RuntimeSample> samples);

    List<RuntimeSample> range(ZonedDateTime from, ZonedDateTime to, int limit);
}
