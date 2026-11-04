package ai.core.server.dataset.store;

import ai.core.utils.JsonUtil;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Q5: concurrent field-level patches must not lose updates. 50 threads each add 100 distinct fields to
 * the same session state; all 5000 must survive.
 *
 * @author stephen
 */
abstract class DatasetRecordStoreConcurrencyTest {
    private static final int THREADS = 50;
    private static final int PATCHES_PER_THREAD = 100;

    private String datasetId;

    protected abstract DatasetRecordStore store();

    @BeforeEach
    void setUpDataset() {
        datasetId = "ds-" + UUID.randomUUID();
        store().upsertBySession(datasetId, "s1", "{\"started\":true}", "agent1", "u1");
    }

    @AfterEach
    void tearDownDataset() {
        store().deleteByDataset(datasetId);
    }

    @Test
    @SuppressFBWarnings("HES_LOCAL_EXECUTOR_SERVICE")
    void concurrentPatchesDoNotLoseFields() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<?>>();
        try {
            for (int thread = 0; thread < THREADS; thread++) {
                int index = thread;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < PATCHES_PER_THREAD; i++) {
                        store().patchBySession(datasetId, "s1", "{\"f_" + index + "_" + i + "\":" + i + "}", "agent1", "u1");
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(180, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        var state = store().findBySession(datasetId, "s1").orElseThrow();
        assertEquals(THREADS * PATCHES_PER_THREAD + 1, JsonUtil.toMap(state.data).size());
    }
}
