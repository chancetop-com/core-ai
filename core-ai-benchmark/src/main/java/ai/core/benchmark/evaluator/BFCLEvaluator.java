package ai.core.benchmark.evaluator;

import ai.core.benchmark.common.BFCLCategory;
import ai.core.benchmark.domain.BFCLFileInfo;
import ai.core.benchmark.domain.BFCLItem;
import ai.core.benchmark.domain.BFCLItemEvalResult;
import ai.core.benchmark.executor.ConcurrentBatchExecutor;
import ai.core.benchmark.inference.BFCLInferenceHandle;
import ai.core.benchmark.loader.BFCLDatasetLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * author: lim chen
 * date: 2025/12/19
 * description: BFCL Benchmark Evaluator with batch processing
 */
public class BFCLEvaluator {
    private static final Logger LOGGER = LoggerFactory.getLogger(BFCLEvaluator.class);

    private final int batchSize;
    private final int threadPoolSize;
    private final AtomicInteger processedCount;
    private final BFCLDatasetLoader datasetLoader;
    private final BFCLInferenceHandle inferenceHandle;

    public BFCLEvaluator(BFCLInferenceHandle inferenceHandle) {
        this.threadPoolSize = Runtime.getRuntime().availableProcessors();
        this.batchSize = 50;
        this.processedCount = new AtomicInteger(0);
        this.datasetLoader = new BFCLDatasetLoader();
        this.inferenceHandle = inferenceHandle;
    }


    private List<BFCLFileInfo> loadDataset(BFCLCategory category, List<String> filterIds) {
        var loadResult = datasetLoader.load(category);
        if (filterIds != null && !filterIds.isEmpty()) {
            return loadResult.stream()
                    .map(fileInfo -> filterFileInfoByIds(fileInfo, filterIds))
                    .filter(fileInfo -> !fileInfo.items.isEmpty())
                    .toList();
        } else {
            return loadResult;
        }
    }

    private BFCLFileInfo filterFileInfoByIds(BFCLFileInfo fileInfo, List<String> filterIds) {
        fileInfo.items = fileInfo.items.stream()
                .filter(item -> filterIds.contains(item.id))
                .toList();
        return fileInfo;
    }

    public void eval(BFCLCategory category, List<String> filterIds) {
        LOGGER.info("Starting evaluation for category: {}", category);

        var fileInfos = loadDataset(category,filterIds);

        if (fileInfos.isEmpty()) {
            LOGGER.warn("No data files found for category: {}", category);
            return;
        }

        // Calculate total items across all files
        int totalItems = fileInfos.stream().mapToInt(f -> f.items.size()).sum();
        LOGGER.info("Loaded {} files with {} total items for evaluation", fileInfos.size(), totalItems);

        // Prepare batch tasks - split each file into batches
        List<Runnable> tasks = new ArrayList<>();
        int totalBatches = 0;

        for (BFCLFileInfo fileInfo : fileInfos) {
            List<List<BFCLItem>> fileBatches = splitIntoBatches(fileInfo.items, batchSize);
            totalBatches += fileBatches.size();

            for (int i = 0; i < fileBatches.size(); i++) {
                final int batchIndex = i;
                final List<BFCLItem> batch = fileBatches.get(i);
                final BFCLFileInfo currentFileInfo = BFCLFileInfo.of(fileInfo.name, category.name().toLowerCase(),fileInfo.path, batch);
                tasks.add(() -> processBatch(currentFileInfo, batchIndex, totalItems));
            }
        }

        LOGGER.info("Split into {} total batches across {} files (batch size: {})",
                totalBatches, fileInfos.size(), batchSize);

        // Execute batch tasks concurrently
        ConcurrentBatchExecutor executor = ConcurrentBatchExecutor.create(threadPoolSize);
        try {
            LOGGER.info("Starting concurrent batch execution with {} threads", threadPoolSize);
            executor.execute(tasks);
            LOGGER.info("All batches completed. Total processed: {}/{}", processedCount.get(), totalItems);
        } finally {
            executor.shutdown();
        }
    }

    private List<List<BFCLItem>> splitIntoBatches(List<BFCLItem> dataset, int batchSize) {
        List<List<BFCLItem>> batches = new ArrayList<>();
        for (int i = 0; i < dataset.size(); i += batchSize) {
            int end = Math.min(i + batchSize, dataset.size());
            batches.add(dataset.subList(i, end));
        }
        return batches;
    }

    private void processBatch(BFCLFileInfo fileInfo, int batchIndex, int totalItems) {
        try {
            LOGGER.info("[{}] Processing batch {} with {} items", fileInfo.name, batchIndex, fileInfo.items.size());

            for (BFCLItem item : fileInfo.items) {
                BFCLItemEvalResult result = runItem(item);
                writeResultToFile(fileInfo, result);
                int processed = processedCount.incrementAndGet();
                logProgress(processed, totalItems);
            }

            LOGGER.info("[{}] Completed batch {} ({} items)", fileInfo.name, batchIndex, fileInfo.items.size());

        } catch (Exception e) {
            LOGGER.error("[{}] Error processing batch {}", fileInfo.name, batchIndex, e);
        }
    }

    private BFCLItemEvalResult runItem(BFCLItem item) {
        return inferenceHandle.handle(item);
    }

    private void writeResultToFile(BFCLFileInfo fileInfo, BFCLItemEvalResult result) {
        datasetLoader.writeResultToFile(fileInfo, result);
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
