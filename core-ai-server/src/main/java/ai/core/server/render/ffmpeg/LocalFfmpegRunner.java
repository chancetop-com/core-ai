package ai.core.server.render.ffmpeg;

import ai.core.server.file.FileService;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs an ffmpeg plan with the binary on the server host, in a temp directory per job. Local development
 * only: ffmpeg is CPU-heavy and of unbounded duration, which is
 * exactly why the deployment path ({@link SandboxFfmpegRunner}) keeps it out of the API process. The
 * version pin still applies — products are filed under cache keys that name an ffmpeg major.
 *
 * @author stephen
 */
public class LocalFfmpegRunner implements FfmpegRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalFfmpegRunner.class);
    private static final int LOG_TAIL_LINES = 30;

    private static String tail(Path log) {
        try {
            var lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - LOG_TAIL_LINES), lines.size()));
        } catch (IOException e) {
            return "(ffmpeg log unavailable)";
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(LocalFfmpegRunner::deleteQuietly);
        } catch (IOException e) {
            LOGGER.warn("failed to clean ffmpeg work dir, path={}", dir, e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.warn("failed to delete ffmpeg temp file, path={}", path, e);
        }
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read inline product " + file, e);
        }
    }

    /** ffprobe ships next to ffmpeg; a configured path like /opt/ffmpeg/bin/ffmpeg maps to /opt/ffmpeg/bin/ffprobe. */
    static String probeBinary(String ffmpegBinary) {
        var lower = ffmpegBinary.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith("ffmpeg")) return ffmpegBinary.substring(0, ffmpegBinary.length() - 6) + "ffprobe";
        if (lower.endsWith("ffmpeg.exe")) return ffmpegBinary.substring(0, ffmpegBinary.length() - 10) + "ffprobe.exe";
        return "ffprobe";
    }

    private static void writeFile(Path workDir, Write write) {
        try {
            Files.writeString(workDir.resolve(FfmpegRunner.safeName(write.fileName())), write.content(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write plan file " + write.fileName(), e);
        }
    }

    @Inject
    FileService fileService;

    private final java.util.function.Supplier<String> binary;
    private volatile String detectedFor;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private volatile Integer detectedMajor;

    public LocalFfmpegRunner(String ffmpegBinary) {
        this(() -> ffmpegBinary);
    }

    /** The binary path may be a live setting; the version probe is redone whenever it changes. */
    public LocalFfmpegRunner(java.util.function.Supplier<String> binary) {
        this.binary = binary;
    }

    private String ffmpegBinary() {
        var path = binary.get();
        return path == null || path.isBlank() ? "ffmpeg" : path.trim();
    }

    @Override
    public Map<String, Product> run(Plan plan) {
        requireFfmpegMajor(plan.expectedFfmpegMajor());
        Path workDir;
        try {
            workDir = Files.createTempDirectory("ffmpeg-plan-");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create ffmpeg work dir", e);
        }
        try {
            for (var download : plan.downloads()) download(download, workDir, plan.stepTimeoutMs());
            for (var write : plan.writes()) writeFile(workDir, write);
            for (var step : plan.steps()) runStep(step, workDir, plan.stepTimeoutMs());
            var products = new LinkedHashMap<String, Product>();
            for (var output : plan.outputs()) {
                var file = workDir.resolve(FfmpegRunner.safeName(output.fileName()));
                if (!Files.isRegularFile(file)) throw new IllegalStateException("ffmpeg plan produced no " + output.fileName());
                if (output.inline()) {
                    products.put(output.kind(), new Product(null, readText(file)));
                } else {
                    products.put(output.kind(), Product.of(fileService.uploadIfAbsent(plan.userId(), output.fileName(), output.contentType(), file)));
                }
            }
            return products;
        } finally {
            deleteRecursively(workDir);
        }
    }

    /** The binary is probed once; a missing binary or a different major fails every plan with the same message. */
    void requireFfmpegMajor(int expected) {
        var ffmpegBinary = ffmpegBinary();
        var major = detectedMajor;
        if (major == null || !ffmpegBinary.equals(detectedFor)) {
            major = detectMajor();
            detectedMajor = major;
            detectedFor = ffmpegBinary;
        }
        if (major == expected) return;
        throw new IllegalStateException("FFMPEG_VERSION_MISMATCH: this pipeline files products under a cache key pinned to ffmpeg major "
            + expected + " but '" + ffmpegBinary + "' on this host reports " + (major == 0 ? "none (not installed or not on PATH)" : major)
            + " — install ffmpeg " + expected + ".x or point the configured ffmpeg path at it");
    }

    public int detectMajor() {
        var ffmpegBinary = ffmpegBinary();
        try {
            var process = new ProcessBuilder(ffmpegBinary, "-version").redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return 0;
            }
            return FfmpegRunner.parseFfmpegMajor(output);
        } catch (IOException e) {
            LOGGER.warn("ffmpeg binary not runnable, binary={}", ffmpegBinary, e);
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    private void download(Download download, Path workDir, long timeoutMs) {
        var target = workDir.resolve(FfmpegRunner.safeName(download.fileName()));
        var request = HttpRequest.newBuilder(URI.create(download.url())).timeout(Duration.ofMillis(Math.max(1000, timeoutMs))).GET().build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(target));
            // an error page must never land where a video is expected
            if (response.statusCode() >= 300) {
                Files.deleteIfExists(target);
                throw new IllegalStateException("download failed (HTTP " + response.statusCode() + "): " + download.fileName());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("download failed: " + download.fileName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("download interrupted: " + download.fileName(), e);
        }
    }

    void runStep(List<String> step, Path workDir, long timeoutMs) {
        var ffmpegBinary = ffmpegBinary();
        var probe = PROBE.equals(FfmpegRunner.binaryFor(step));
        var command = new ArrayList<String>();
        command.add(probe ? probeBinary(ffmpegBinary) : ffmpegBinary);
        if (!probe) {
            command.add("-nostdin");
            command.add("-hide_banner");
        }
        command.addAll(FfmpegRunner.arguments(step));
        var log = workDir.resolve(".ffmpeg-" + System.nanoTime() + ".log");
        try {
            var process = new ProcessBuilder(command).directory(workDir.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("ffmpeg step timed out after " + timeoutMs + "ms: " + String.join(" ", step));
            }
            if (process.exitValue() != 0)
                throw new IllegalStateException("ffmpeg step failed (exit " + process.exitValue() + "): " + String.join(" ", step) + "\n" + tail(log));
            LOGGER.debug("ffmpeg step done: {}", step);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start ffmpeg: " + ffmpegBinary, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ffmpeg step interrupted", e);
        }
    }

}
