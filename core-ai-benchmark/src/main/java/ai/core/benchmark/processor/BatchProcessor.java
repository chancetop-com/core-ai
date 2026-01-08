package ai.core.benchmark.processor;

import ai.core.benchmark.executor.ConcurrentBatchExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * author: lim chen
 * date: 2026/01/07
 * description: Generic batch processor with concurrent execution and progress tracking
 * @param <T> Item type to process
 * @param <R> Result type from processing
 */
public class BatchProcessor<T, R> {
    private static final Logger LOGGER = LoggerFactory.getLogger(BatchProcessor.class);

    private final int batchSize;
    private final int threadPoolSize;
    private final Function<T, R> itemProcessor;
    private final BiConsumer<T, R> resultWriter;

    public BatchProcessor(int batchSize,
                          int threadPoolSize,
                          Function<T, R> itemProcessor,
                          BiConsumer<T, R> resultWriter) {
        this.batchSize = batchSize;
        this.threadPoolSize = threadPoolSize;
        this.itemProcessor = itemProcessor;
        this.resultWriter = resultWriter;
    }

    public void process(List<T> items) {
        if (items.isEmpty()) {
            LOGGER.warn("No items to process");
            return;
        }

        int totalItems = items.size();
        LOGGER.info("Processing {} items with batch size {} and {} threads",
                    totalItems, batchSize, threadPoolSize);

        // Split into batches
        List<List<T>> batches = splitIntoBatches(items);
        LOGGER.info("Split into {} batches", batches.size());

        // Create tasks for concurrent execution
        AtomicInteger processedCount = new AtomicInteger(0);
        List<Runnable> tasks = new ArrayList<>();

        for (int i = 0; i < batches.size(); i++) {
            final int batchIndex = i;
            final List<T> batch = batches.get(i);
            tasks.add(() -> processBatch(batch, batchIndex, processedCount, totalItems));
        }

        // Execute tasks concurrently
        ConcurrentBatchExecutor executor = ConcurrentBatchExecutor.create(threadPoolSize);
        try {
            LOGGER.info("Starting concurrent batch execution");
            executor.execute(tasks);
            LOGGER.info("All batches completed. Total processed: {}/{}", processedCount.get(), totalItems);
        } finally {
            executor.shutdown();
        }
    }

    private List<List<T>> splitIntoBatches(List<T> items) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += batchSize) {
            int end = Math.min(i + batchSize, items.size());
            batches.add(items.subList(i, end));
        }
        return batches;
    }

    private void processBatch(List<T> batch, int batchIndex, AtomicInteger processedCount, int totalItems) {
        try {
            LOGGER.info("Processing batch {} with {} items", batchIndex, batch.size());

            for (T item : batch) {
                R result = itemProcessor.apply(item);
                resultWriter.accept(item, result);
                int processed = processedCount.incrementAndGet();
                logProgress(processed, totalItems);
            }

            LOGGER.info("Completed batch {} ({} items)", batchIndex, batch.size());

        } catch (Exception e) {
            LOGGER.error("Error processing batch {}", batchIndex, e);
        }
    }

    private void logProgress(int processed, int total) {
        if (processed % 10 == 0 || processed == total) {
            double progress = (double) processed / total * 100;
            String progressBar = generateProgressBar(progress);
            LOGGER.info("Progress: {} [{}/{}]", progressBar, processed, total);
        }
    }

    private String generateProgressBar(double percentage) {
        int barLength = 30;
        int filled = (int) (barLength * percentage / 100);
        int empty = barLength - filled;

        return "[" +
                "=".repeat(Math.max(0, filled)) +
                ">".repeat(filled > 0 && empty > 0 ? 1 : 0) +
                " ".repeat(Math.max(0, empty - (filled > 0 && empty > 0 ? 1 : 0))) +
                "]" +
                String.format(" %.1f%%", percentage);
    }
}
