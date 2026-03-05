package ai.core.benchmark.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * author: lim chen
 * date: 2025/12/22
 * description: Generic concurrent batch task executor for running tasks in parallel
 */
public class ConcurrentBatchExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrentBatchExecutor.class);

    public static ConcurrentBatchExecutor create() {
        return new ConcurrentBatchExecutor(Runtime.getRuntime().availableProcessors());
    }

    public static ConcurrentBatchExecutor create(int threadPoolSize) {
        return new ConcurrentBatchExecutor(threadPoolSize);
    }

    private final int threadPoolSize;
    private final ExecutorService executorService;

    public ConcurrentBatchExecutor(int threadPoolSize) {
        this.threadPoolSize = threadPoolSize;
        this.executorService = Executors.newFixedThreadPool(threadPoolSize);
    }



    /**
     * Execute a list of tasks concurrently and wait for all to complete
     */
    public void execute(List<Runnable> tasks) {
        LOGGER.info("Submitting {} tasks to executor with {} threads", tasks.size(), threadPoolSize);

        // Submit all tasks
        List<CompletableFuture<Void>> futures = tasks.stream()
                .map(task -> CompletableFuture.runAsync(task, executorService))
                .toList();

        // Wait for all tasks to complete
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        LOGGER.info("All {} tasks completed", tasks.size());
    }

    /**
     * Shutdown the executor service gracefully
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
                if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    LOGGER.error("ExecutorService did not terminate");
                }
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }


}
