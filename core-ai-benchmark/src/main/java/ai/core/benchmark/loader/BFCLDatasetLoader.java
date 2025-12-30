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
import java.util.Map;
import java.util.stream.Stream;

/**
 * author: lim chen
 * date: 2025/12/19
 * description: BFCL Dataset Loader for loading benchmark data and write result
 */
public class BFCLDatasetLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(BFCLDatasetLoader.class);
    private final String workDir;
    private final BFCLDownloader datasetDownloader;

    public BFCLDatasetLoader() {
        this.workDir = initWorkDir();
        this.datasetDownloader = new BFCLDownloader();
    }

    private String initWorkDir() {
        var resourceUrl = Paths.get("").toAbsolutePath()
                .getParent()
                .resolve("build")
                .resolve("core-ai-benchmark")
                .resolve("resources")
                .resolve("main")
                .resolve("dataset")
                .resolve("BFCL");
        return resourceUrl.toString();
    }

    public List<BFCLFileInfo> load(BFCLCategory category) {
        downloadDataset();
        return readFiles(category);
    }

    public void writeResultToFile(BFCLFileInfo fileInfo, BFCLItemEvalResult result) {
        var path = createTargetFile(fileInfo);
        writeResultToFile(path, result);
    }

    private Path createTargetFile(BFCLFileInfo fileInfo) {
        var path = Paths.get(this.workDir)
                .resolve("result")
                .resolve("gpt-5-nano-2025-08-07-FC")
                .resolve(fileInfo.category);
        try {
            Files.createDirectories(path);
            var fileNameSplit = fileInfo.name.split("\\.");
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

    private void writeResultToFile(Path path, BFCLItemEvalResult result) {
        LOGGER.debug("Writing result to file: {}", path.toString());
        ObjectMapper mapper = new ObjectMapper();
        try (FileChannel channel = FileChannel.open(
                path,
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
                    return category.getTypes().stream().anyMatch(name::contains);
                })
                .peek(entry -> LOGGER.info("Reading file: {}", entry.getKey()))
                .map(entry -> {
                    var path = entry.getValue();
                    var name = entry.getKey();
                    var items = readFile(path);
                    return BFCLFileInfo.of(name, category.name().toLowerCase(), path.toString(), items);

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
