package ai.core.cli.trace;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class HttpTraceUploaderTest {

    @Test
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("HES_LOCAL_EXECUTOR_SERVICE")
    void flushWaitsForQueuedUploads() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var uploadStarted = new CountDownLatch(1);
            var finishUpload = new CountDownLatch(1);
            executor.submit(() -> {
                uploadStarted.countDown();
                finishUpload.await(30, TimeUnit.SECONDS);
                return null;
            });
            assertTrue(uploadStarted.await(30, TimeUnit.SECONDS));

            var flushed = CompletableFuture.supplyAsync(() -> HttpTraceUploader.flush(executor, Duration.ofSeconds(15)));
            Thread.sleep(200);
            assertFalse(flushed.isDone(), "flush must wait for the in-flight upload");

            finishUpload.countDown();
            assertTrue(flushed.get(30, TimeUnit.SECONDS), "flush should finish once the upload completes");
        } finally {
            executor.shutdownNow();
        }
    }
}
