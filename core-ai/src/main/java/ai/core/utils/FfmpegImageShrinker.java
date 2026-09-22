package ai.core.utils;

import ai.core.vender.VendorManagement;
import ai.core.vender.vendors.FfmpegVendor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * ffmpeg backed {@link ImageDownscaler.Shrinker} for hosts that cannot run the AWT image pipeline, i.e. the
 * GraalVM native CLI. Chat completions are stateless, so every inline image stays in the history and is
 * resent on every later turn; without a shrinker a single 6MB keyframe or screenshot pushes the request body
 * past the 10MB limit of the CLI proxy and kills the turn with an empty 400.
 * The image is re-encoded to jpeg once, when the tool result or attachment enters the history, downscaled to
 * {@link ImageDownscaler#MAX_EDGE} and flattened onto white (jpeg carries no alpha, the AWT shrinker fills
 * white for the same reason). Dimensions are read from the payload header, so an image that is already
 * within the budget is never touched.
 * A missing binary, an undecodable payload or a failing ffmpeg keeps the caller's payload as is, the
 * shrinker never fails a turn.
 *
 * @author stephen
 */
public class FfmpegImageShrinker implements ImageDownscaler.Shrinker {
    private static final Logger LOGGER = LoggerFactory.getLogger(FfmpegImageShrinker.class);
    private static final int JPEG_QUALITY = 3;
    private static final int PROCESS_TIMEOUT_SECONDS = 60;
    private static final int MIN_DIMENSION = 2;

    /**
     * @return the size the payload has to be scaled to, or null when it is already within
     *         {@link ImageDownscaler#MAX_EDGE} and {@link ImageDownscaler#MAX_BYTES}, or when its
     *         dimensions cannot be read
     */
    static Size targetSize(byte[] source, String format) {
        var dimensions = dimensions(source, format);
        if (dimensions == null) return null;
        var longestEdge = Math.max(dimensions.width(), dimensions.height());
        if (source.length <= ImageDownscaler.MAX_BYTES && longestEdge <= ImageDownscaler.MAX_EDGE) return null;
        return downscaled(dimensions);
    }

    static Size dimensions(byte[] bytes, String format) {
        if (bytes == null || format == null) return null;
        return switch (format) {
            case "png" -> pngDimensions(bytes);
            case "jpeg" -> jpegDimensions(bytes);
            case "gif" -> gifDimensions(bytes);
            default -> null;
        };
    }

    private static Size pngDimensions(byte[] bytes) {
        if (bytes.length < 24) return null;
        return new Size(readIntBigEndian(bytes, 16), readIntBigEndian(bytes, 20));
    }

    private static Size gifDimensions(byte[] bytes) {
        if (bytes.length < 10) return null;
        return new Size(readShortLittleEndian(bytes, 6), readShortLittleEndian(bytes, 8));
    }

    private static Size jpegDimensions(byte[] bytes) {
        var index = 2;
        while (index + 3 < bytes.length) {
            if ((bytes[index] & 0xFF) != 0xFF) {
                index++;
                continue;
            }
            var marker = bytes[index + 1] & 0xFF;
            if (marker == 0xFF || marker == 0x00 || marker == 0x01 || marker >= 0xD0 && marker <= 0xD9) {
                index += 2;
                continue;
            }
            if (isStartOfFrame(marker)) {
                if (index + 8 >= bytes.length) return null;
                return new Size(readShortBigEndian(bytes, index + 7), readShortBigEndian(bytes, index + 5));
            }
            // every other segment carries its payload length, walk to the next marker
            var length = readShortBigEndian(bytes, index + 2);
            if (length < 2) return null;
            index += 2 + length;
        }
        return null;
    }

    private static boolean isStartOfFrame(int marker) {
        return marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
    }

    private static Size downscaled(Size dimensions) {
        var longestEdge = Math.max(dimensions.width(), dimensions.height());
        if (longestEdge <= ImageDownscaler.MAX_EDGE) {
            return new Size(even(dimensions.width()), even(dimensions.height()));
        }
        var scale = (double) ImageDownscaler.MAX_EDGE / longestEdge;
        return new Size(even((int) Math.round(dimensions.width() * scale)), even((int) Math.round(dimensions.height() * scale)));
    }

    // yuv420p encoders need even dimensions
    private static int even(int value) {
        var adjusted = Math.max(MIN_DIMENSION, value);
        return adjusted % 2 == 0 ? adjusted : adjusted + 1;
    }

    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            LOGGER.debug("inline image is not valid base64, keeping it as is: {}", e.getMessage());
            return null;
        }
    }

    private static Path vendoredExecutable() {
        return VendorManagement.getInstance().getExecutablePath(FfmpegVendor.class);
    }

    private static Process start(Path ffmpeg, Path input, Path output, Size target) throws IOException {
        var filter = String.format("color=c=white:s=%dx%d[bg];[0:v]scale=%d:%d:flags=bicubic[fg];[bg][fg]overlay[v]",
                target.width(), target.height(), target.width(), target.height());
        return new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-y",
                "-i", input.toString(), "-filter_complex", filter, "-map", "[v]", "-frames:v", "1",
                "-q:v", String.valueOf(JPEG_QUALITY), output.toString())
                .redirectErrorStream(true)
                .start();
    }

    private static String readOutput(Process process) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            var output = new StringBuilder();
            var line = reader.readLine();
            while (line != null) {
                output.append(line).append('\n');
                line = reader.readLine();
            }
            return output.toString();
        }
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null) return;
        try {
            SystemUtil.deleteDirectory(directory);
        } catch (IOException e) {
            LOGGER.debug("failed to delete temp directory {}, error={}", directory, e.getMessage());
        }
    }

    private static int readShortBigEndian(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static int readShortLittleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static int readIntBigEndian(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    private final Supplier<Path> executable;
    private final AtomicBoolean resolutionNotice = new AtomicBoolean();
    private volatile boolean unavailable;

    public FfmpegImageShrinker() {
        this(FfmpegImageShrinker::vendoredExecutable);
    }

    FfmpegImageShrinker(Supplier<Path> executable) {
        this.executable = executable;
    }

    /**
     * @return the downscaled jpeg payload, or null to keep the original: when it is already within
     *         {@link ImageDownscaler#MAX_EDGE} and {@link ImageDownscaler#MAX_BYTES}, when its dimensions
     *         cannot be read, when ffmpeg is unavailable, or when the transcode fails
     */
    @Override
    public String shrinkToJpeg(String base64) {
        if (base64 == null || base64.isEmpty() || unavailable) return null;
        var source = decode(base64);
        if (source == null) return null;
        var format = ImageFormats.detect(source);
        var target = targetSize(source, format);
        if (target == null) return null;
        var ffmpeg = resolveExecutable();
        if (ffmpeg == null) return null;
        var shrunk = transcode(ffmpeg, source, format, target);
        return shrunk == null ? null : Base64.getEncoder().encodeToString(shrunk);
    }

    private Path resolveExecutable() {
        if (unavailable) return null;
        if (resolutionNotice.compareAndSet(false, true)) {
            LOGGER.info("resolving ffmpeg to shrink inline images; its first use downloads a one time ~30MB binary");
        }
        try {
            return executable.get();
        } catch (RuntimeException e) {
            unavailable = true;
            LOGGER.warn("ffmpeg is unavailable, inline images are sent unshrunk, error={}", e.getMessage());
            return null;
        }
    }

    private byte[] transcode(Path ffmpeg, byte[] source, String format, Size target) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("core-ai-image-");
            var input = directory.resolve("input." + format);
            var output = directory.resolve("output.jpg");
            Files.write(input, source);
            var process = start(ffmpeg, input, output, target);
            var log = readOutput(process);
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LOGGER.warn("ffmpeg timed out after {}s while shrinking an inline image, output={}",
                        PROCESS_TIMEOUT_SECONDS, log.trim());
                return null;
            }
            if (process.exitValue() != 0 || !Files.exists(output)) {
                LOGGER.warn("ffmpeg failed to shrink an inline image, exitCode={}, output={}", process.exitValue(), log.trim());
                return null;
            }
            return Files.readAllBytes(output);
        } catch (IOException e) {
            LOGGER.warn("failed to shrink an inline image with ffmpeg, error={}", e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            deleteQuietly(directory);
        }
    }

    record Size(int width, int height) {
    }
}
