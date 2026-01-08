package ai.core.benchmark.evaluator;

import ai.core.benchmark.common.BFCLCategory;
import ai.core.benchmark.domain.BFCLFileInfo;
import ai.core.benchmark.domain.BFCLItem;
import ai.core.benchmark.domain.BFCLItemEvalResult;
import ai.core.benchmark.inference.BFCLInferenceHandle;
import ai.core.benchmark.loader.BFCLDatasetLoader;
import ai.core.benchmark.processor.BatchProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * author: lim chen
 * date: 2025/12/19
 * description: BFCL Benchmark Evaluator - lightweight coordinator
 */
public class BFCLEvaluator {
    private static final Logger LOGGER = LoggerFactory.getLogger(BFCLEvaluator.class);

    private final BFCLDatasetLoader datasetLoader;
    private final BFCLInferenceHandle inferenceHandle;
    private final BatchProcessor<ItemContext, BFCLItemEvalResult> batchProcessor;

    public BFCLEvaluator(BFCLInferenceHandle inferenceHandle, String resultDirName) {
        this.datasetLoader = new BFCLDatasetLoader(resultDirName);
        this.inferenceHandle = inferenceHandle;
        this.batchProcessor = new BatchProcessor<>(
                50,
                Runtime.getRuntime().availableProcessors(),
                this::processItem,
                this::writeResult
        );
    }

    /**
     * Context for processing an item with its file metadata
     */
    private record ItemContext(String fileName, String category, BFCLItem item) {
    }


    private List<BFCLFileInfo> loadDataset(BFCLCategory category, List<String> filterIds, boolean skipCompleted) {
        var loadResult = skipCompleted ? datasetLoader.loadUncompleted(category) : datasetLoader.load(category);

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
        eval(category, filterIds, true);
    }

    public void eval(BFCLCategory category, List<String> filterIds, boolean skipCompleted) {
        LOGGER.info("Starting evaluation for category: {} (skipCompleted: {})", category, skipCompleted);

        // Load dataset
        var fileInfos = loadDataset(category, filterIds, skipCompleted);

        if (fileInfos.isEmpty()) {
            LOGGER.warn("No data files found for category: {}", category);
            return;
        }

        // Flatten all items with their file metadata
        List<ItemContext> allItems = new ArrayList<>();
        for (BFCLFileInfo fileInfo : fileInfos) {
            for (BFCLItem item : fileInfo.items) {
                allItems.add(new ItemContext(fileInfo.name, fileInfo.category, item));
            }
        }

        int totalItems = allItems.size();
        LOGGER.info("Loaded {} files with {} total items for evaluation", fileInfos.size(), totalItems);

        // Process all items using batch processor
        batchProcessor.process(allItems);
    }

    private BFCLItemEvalResult processItem(ItemContext context) {
        LOGGER.info("[{}] Processing item: {}", context.fileName, context.item.id);
        return inferenceHandle.handle(context.item);
    }

    private void writeResult(ItemContext context, BFCLItemEvalResult result) {
        LOGGER.debug("[{}] Writing result for item: {}", context.fileName, context.item.id);
        datasetLoader.writeResultToFile(context.fileName, context.category, result);
    }

}
