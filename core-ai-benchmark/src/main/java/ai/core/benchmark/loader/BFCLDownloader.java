package ai.core.benchmark.loader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * author: lim chen
 * date: 2025/12/26
 * description: Utility for downloading BFCL dataset from GitHub
 */
public class BFCLDownloader {
    private static final Logger LOGGER = LoggerFactory.getLogger(BFCLDownloader.class);

    private static final String GORILLA_REPO_URL = "https://gh-proxy.org/https://github.com/ShishirPatil/gorilla.git";
    private static final String EVAL_PATH = "berkeley-function-call-leaderboard/bfcl_eval/data";

    public void download(String workDir) {
        Path bfDir = Paths.get(workDir);
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "gorilla-" + System.currentTimeMillis());

        try {
            LOGGER.info("Cloning gorilla repository (this may take a few minutes)...");
            cloneRepository(tempDir);

            Path sourceDir = tempDir.resolve(EVAL_PATH);
            if (!Files.exists(sourceDir)) {
                throw new IOException("data directory not found: " + sourceDir);
            }

            copyDirectory(sourceDir, bfDir);

            LOGGER.info("BFCL dataset downloaded to: {}", bfDir);

        } catch (Exception e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Download interrupted", e);
        } finally {
            cleanup(tempDir);
        }
    }


    private void cloneRepository(Path tempDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("git", "clone", "--depth", "1", GORILLA_REPO_URL, tempDir.toString());
        pb.redirectErrorStream(true);
        LOGGER.info("process: {}", pb.command().toString());
        Process process = pb.start();

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Git clone failed");
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        Path targetWithSourceName = target.resolve(source.getFileName());

        try (Stream<Path> stream = Files.walk(source)) {
            for (Path src : stream.toList()) {
                Path dst = targetWithSourceName.resolve(source.relativize(src));
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void cleanup(Path tempDir) {
        if (Files.exists(tempDir)) {
            try (Stream<Path> stream = Files.walk(tempDir)) {
                stream.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(file -> {
                            if (!file.delete()) {
                                LOGGER.warn("Failed to delete: {}", file);
                            }
                        });
            } catch (IOException e) {
                LOGGER.warn("Cleanup failed: {}", e.getMessage());
            }
        }

    }
}
