package ai.core.benchmark.loader;

import ai.core.benchmark.common.BFCLCategory;
import ai.core.benchmark.domain.BFCLFileInfo;
import ai.core.benchmark.domain.BFCLItem;
import ai.core.benchmark.domain.BFCLItemEvalResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * author: lim chen
 * date: 2025/12/19
 * description: BFCL Dataset Loader for loading benchmark data and write result
 */
public class BFCLDatasetLoader implements
        ResumableLoader<BFCLCategory, BFCLFileInfo>,
        ResultWriter<BFCLItemEvalResult> {
    private static final Logger LOGGER = LoggerFactory.getLogger(BFCLDatasetLoader.class);
    private final String workDir;
    private final BFCLDownloader datasetDownloader;
    private final String resultDirName;

    public BFCLDatasetLoader(String resultDirName) {
        this.workDir = initWorkDir();
        this.datasetDownloader = new BFCLDownloader();
        this.resultDirName = resultDirName;
    }

    private String initWorkDir() {
        var workDir = Paths.get(System.getProperty("user.home"))
                .resolve(".cache")
                .resolve("core-ai-benchmark")
                .resolve("BFCL");
        return workDir.toString();
    }

    @Override
    public List<BFCLFileInfo> load(BFCLCategory category) {
        downloadDataset();
        return readFiles(category);
    }

    @Override
    public List<BFCLFileInfo> loadUncompleted(BFCLCategory category) {
        LOGGER.info("Loading uncompleted items for category: {}", category);
        var allFiles = load(category);
        var completedIds = getCompletedItemIds(category);

        if (completedIds.isEmpty()) {
            return allFiles;
        }

        // Filter out completed items
        return allFiles.stream()
                .map(fileInfo -> filterOutCompletedItems(fileInfo, completedIds))
                .filter(fileInfo -> !fileInfo.items.isEmpty())
                .toList();
    }

    private BFCLFileInfo filterOutCompletedItems(BFCLFileInfo fileInfo, List<String> completedIds) {
        var uncompletedItems = fileInfo.items.stream()
                .filter(item -> !completedIds.contains(item.id))
                .toList();
        return BFCLFileInfo.of(fileInfo.name, fileInfo.category, fileInfo.path, uncompletedItems);
    }


    public List<String> getCompletedItemIds(BFCLCategory category) {
        var resultDir = Paths.get(this.workDir)
                .resolve("result")
                .resolve(this.resultDirName)
                .resolve(category.name().toLowerCase(Locale.ROOT));

        if (!Files.exists(resultDir)) {
            LOGGER.info("No result directory found, starting from scratch");
            return List.of();
        }

        var completedIds = new ArrayList<String>();
        try (Stream<Path> files = Files.list(resultDir)) {
            for (Path file : files.toList()) {
                if (file.getFileName().toString().endsWith("_result.json")) {
                    completedIds.addAll(readCompletedIdsFromFile(file));
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Error reading completed items, will process all items", e);
            return List.of();
        }

        LOGGER.info("Found {} completed items for category {}", completedIds.size(), category);
        return completedIds;
    }

    private List<String> readCompletedIdsFromFile(Path file) {
        var ids = new ArrayList<String>();
        ObjectMapper mapper = new ObjectMapper();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            while (reader.ready()) {
                var line = reader.readLine();
                var result = mapper.readValue(line, BFCLItemEvalResult.class);
                ids.add(result.id);
            }
        } catch (IOException e) {
            LOGGER.warn("Error reading file: {}", file, e);
        }
        return ids;
    }

    public Path getTargetFilePath(String fileName, String category) {
        var path = Paths.get(this.workDir)
                .resolve("result")
                .resolve(this.resultDirName)
                .resolve(category);
        try {
            Files.createDirectories(path);
            var fileNameSplit = fileName.split("\\.");
            var targetFileName = fileNameSplit[0] + "_result." + fileNameSplit[1];
            var targetFilePath = path.resolve(targetFileName);
            if (!Files.exists(targetFilePath)) {
                LOGGER.info("Creating file: {}", targetFilePath);
                Files.createFile(targetFilePath);
            }
            return targetFilePath;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void writeResultToFile(Path filePath, BFCLItemEvalResult result) {
        LOGGER.debug("Writing result to file: {}", filePath.toString());
        ObjectMapper mapper = new ObjectMapper();
        try (FileChannel channel = FileChannel.open(
                filePath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
        )) {
            var json = mapper.writeValueAsString(result);
            ByteBuffer buffer = StandardCharsets.UTF_8.encode(json + System.lineSeparator());
            var num = channel.write(buffer);
            LOGGER.debug("Wrote {} bytes to file", num);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void writeResultToFile(String fileName, String category, BFCLItemEvalResult result) {
        var filePath = getTargetFilePath(fileName, category);
        writeResultToFile(filePath, result);
    }

    private boolean isDatasetExist() {
        if (!Files.exists(Paths.get(this.workDir))) {
            return false;
        }
        try (Stream<Path> stream = Files.list(Paths.get(this.workDir))) {
            return stream.findAny().isPresent();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void downloadDataset() {
        LOGGER.info("Using resource directory: {}", Paths.get(workDir).toAbsolutePath());

        if (isDatasetExist()) {
            LOGGER.info("BFCL dataset already available");
            return;
        }
        LOGGER.info("BFCL dataset not found, starting download...");
        datasetDownloader.download(workDir);
    }

    private Map<String, Path> findDatasetFiles() {
        var files = new HashMap<String, Path>();
        try (Stream<Path> stream = Files.list(Paths.get(workDir).resolve("data"))) {
            for (Path path : stream.toList()) {
                if (path.getFileName().toString().startsWith("BFCL_v4_")) {
                    files.put(path.getFileName().toString(), path);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return files;
    }

    private List<BFCLFileInfo> readFiles(BFCLCategory category) {
        LOGGER.info("Reading dataset files...");
        var files = findDatasetFiles();
        return files.entrySet().stream()
                .filter(entry -> {
                    var name = entry.getKey();
                    return category.getTypes().stream().anyMatch(partName -> name.startsWith("BFCL_v4_" + partName));
                })
                .peek(entry -> LOGGER.info("Reading file: {}", entry.getKey()))
                .map(entry -> {
                    var path = entry.getValue();
                    var name = entry.getKey();
                    var items = readFile(path);
                    return BFCLFileInfo.of(name, category.name().toLowerCase(Locale.ROOT), path.toString(), items);

                }).toList();

    }

    private List<BFCLItem> readFile(Path path) {
        var items = new ArrayList<BFCLItem>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            while (reader.ready()) {
                var line = reader.readLine();
                var item = BFCLItem.fromJson(line);
                items.add(item);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return items;
    }

}
