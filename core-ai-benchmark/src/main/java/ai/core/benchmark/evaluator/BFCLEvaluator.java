package ai.core.benchmark.evaluator;

import ai.core.agent.Agent;
import ai.core.benchmark.domain.BFCLItem;
import ai.core.benchmark.domain.BFCLItemAgentResult;
import ai.core.benchmark.evaluator.handle.BFCLAgentHandle;
import ai.core.benchmark.executor.ConcurrentBatchExecutor;
import ai.core.benchmark.loader.BFCLDatasetLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * author: lim chen
 * date: 2025/12/19
 * description: BFCL Benchmark Evaluator with concurrent agent execution
 */
public class BFCLEvaluator implements Evaluator {
    private static final Logger LOGGER = LoggerFactory.getLogger(BFCLEvaluator.class);

    private final List<BFCLDatasetLoader> loaders;
    private final int splitSize;
    private final int threadPoolSize;
    private final Map<String, BFCLItemAgentResult> results;
    private final BFCLAgentHandle agentHandle;

    public BFCLEvaluator(List<BFCLDatasetLoader> loaders, BFCLAgentHandle agentHandle) {
        this(loaders, agentHandle, Runtime.getRuntime().availableProcessors(), 50);
    }

    public BFCLEvaluator(List<BFCLDatasetLoader> loaders, BFCLAgentHandle agentHandle, int threadPoolSize, int splitSize) {
        this.loaders = loaders;
        this.threadPoolSize = threadPoolSize;
        this.splitSize = splitSize;
        this.results = new ConcurrentHashMap<>();
        this.agentHandle = agentHandle;
    }

    @Override
    public void evaluate(Supplier<Agent> agentSupplier) {
        // Calculate total items for progress tracking
        int totalItems = loaders.stream()
                .mapToInt(loader -> loader.getAllItems().size())
                .sum();

        LOGGER.info("Starting evaluation for {} items across {} categories", totalItems, loaders.size());

        List<BatchTask> tasks = prepareBatchTasks(agentSupplier.get());
        executeBatchTasks(tasks, totalItems);
    }

    private List<BatchTask> prepareBatchTasks(Agent agent) {
        List<BatchTask> tasks = new ArrayList<>();

        for (BFCLDatasetLoader loader : loaders) {
            String category = loader.getCategory();
            List<List<BFCLItem>> splits = loader.splitDataset(splitSize);
            LOGGER.info("Category: {} - {} items split into {} batches",
                    category, loader.getAllItems().size(), splits.size());

            for (int batchIndex = 0; batchIndex < splits.size(); batchIndex++) {
                List<BFCLItem> batch = splits.get(batchIndex);
                BatchTask task = new BatchTask(agentHandle, agent, category, batch, batchIndex);
                tasks.add(task);
            }
        }

        return tasks;
    }

    private void executeBatchTasks(List<BatchTask> tasks, int totalItems) {
        ConcurrentBatchExecutor executor = ConcurrentBatchExecutor.create(threadPoolSize);
        EvaluationStatistics statistics = new EvaluationStatistics();

        tasks.forEach(task -> task.setRuntimeContext(results, statistics, totalItems));

        try {
            executor.execute(new ArrayList<>(tasks));
        } finally {
            executor.shutdown();
        }
    }


    //todo  重构，整合到task

    private void logProgress(int processed, int total) {
        if (processed % 10 == 0 || processed == total) {
            double progress = (double) processed / total * 100;

            String progressBar = generateProgressBar(progress);

            LOGGER.info("Progress: {} [{}/{}]",
                    progressBar, processed, total);
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

    // Inner class to encapsulate batch task execution
    private class BatchTask implements Runnable {
        final BFCLAgentHandle fcHandle;
        final String category;
        final List<BFCLItem> batch;
        final int batchIndex;
        final Agent agent;

        private Map<String, BFCLItemAgentResult> results;
        private EvaluationStatistics statistics;
        private int totalItems;

        BatchTask(BFCLAgentHandle fcHandle, Agent agent, String category, List<BFCLItem> batch, int batchIndex) {
            this.category = category;
            this.batch = batch;
            this.batchIndex = batchIndex;
            this.fcHandle = fcHandle;
            this.agent = agent;
        }

        void setRuntimeContext(Map<String, BFCLItemAgentResult> results,
                               EvaluationStatistics statistics,
                               int totalItems) {
            this.results = results;
            this.statistics = statistics;
            this.totalItems = totalItems;
        }

        @Override
        public void run() {
            try {
                for (BFCLItem item : batch) {
                    BFCLItemAgentResult result = fcHandle.handle(agent, item);
                    results.put(result.id, result);

                    int processed = statistics.incrementProcessed();
                    logProgress(processed, totalItems);
                }

                LOGGER.info("[{}] Completed batch {} ({} items)",
                        category, batchIndex, batch.size());

            } catch (Exception e) {
                LOGGER.error("[{}] Error processing batch {}", category, batchIndex, e);
            }
        }
    }

    private static class EvaluationStatistics {
        private final AtomicInteger processed = new AtomicInteger(0);

        int incrementProcessed() {
            return processed.incrementAndGet();
        }

        int getProcessed() {
            return processed.get();
        }

    }
}
