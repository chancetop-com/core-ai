package ai.core.benchmark.loader;

import ai.core.benchmark.domain.BFCLItem;
import ai.core.benchmark.domain.BFCLItemGroundTruth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * author: lim chen
 * date: 2025/12/19
 * description: BFCL Dataset Loader for loading benchmark data and answers
 */
public class BFCLDatasetLoader implements DatasetLoader<BFCLItem, BFCLItemGroundTruth> {
    private static final Logger LOGGER = LoggerFactory.getLogger(BFCLDatasetLoader.class);
    private final String category;
    private final List<BFCLItem> items = new ArrayList<>();
    private final Map<String, BFCLItemGroundTruth> answersMap = new HashMap<>();

    public BFCLDatasetLoader(String category) {
        this.category = category;
        load();
    }

    @Override
    public void load() {
        loadItems();
        loadAnswers();
    }

    @Override
    public List<BFCLItem> getAllItems() {
        return items;
    }


    private void loadItems() {
        String datasetPath = String.format("dataset/BFCL/data/BFCL_v4_%s.json", category);
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(datasetPath)) {
            if (is == null) {
                throw new RuntimeException("Dataset file not found: " + datasetPath);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        BFCLItem item = BFCLItem.fromJson(line);
                        items.add(item);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load dataset: " + datasetPath, e);
        }
    }

    private void loadAnswers() {
        String answerPath = String.format("dataset/BFCL/data/possible_answer/BFCL_v4_%s.json", category);
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(answerPath)) {
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.trim().isEmpty()) {
                            BFCLItemGroundTruth answer = BFCLItemGroundTruth.fromJson(line);
                            answersMap.put(answer.id, answer);
                        }
                    }
                }
            }
        } catch (IOException e) {
            // Answers file might not exist for some categories, that's okay
            LOGGER.warn("Warning: No answer file found for category: {}", category);
        }
    }


    @Override
    public List<List<BFCLItem>> splitDataset(int num) {
        if (num <= 0 || items.isEmpty()) {
            return List.of();
        }

        List<List<BFCLItem>> splits = new ArrayList<>();
        int splitSize = (int) Math.ceil((double) items.size() / num);

        for (int i = 0; i < num; i++) {
            int start = i * splitSize;
            int end = Math.min(start + splitSize, items.size());
            if (start < items.size()) {
                splits.add(new ArrayList<>(items.subList(start, end)));
            }
        }

        return splits;
    }

    @Override
    public List<BFCLItem> getLimitItems(int limit) {
        if (items.isEmpty() || limit <= 0) {
            return List.of();
        }
        return items.stream().limit(limit).toList();
    }

    @Override
    public BFCLItemGroundTruth getOneAnswer(String itemId) {
        return answersMap.get(itemId);
    }

    @Override
    public void officialization(String outPath) {

    }

    // Additional utility methods

    public String getCategory() {
        return category;
    }
}
