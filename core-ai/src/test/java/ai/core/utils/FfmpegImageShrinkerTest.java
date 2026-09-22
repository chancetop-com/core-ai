package ai.core.utils;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The ffmpeg backed shrinker runs only for hosts without AWT, so the pure parts (payload header, budget,
 * downscale math and the unavailable path) are covered here, and the transcode itself is enabled by
 * pointing {@code CORE_AI_FFMPEG} at a real ffmpeg binary.
 *
 * @author stephen
 */
class FfmpegImageShrinkerTest {
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private static Path ffmpeg() {
        var configured = System.getenv("CORE_AI_FFMPEG");
        return configured == null || configured.isBlank() ? null : Path.of(configured);
    }

    private static void runFfmpeg(Path ffmpeg, String... args) throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add(ffmpeg.toString());
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-y");
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    private static byte[] padded(byte[] header, int length) {
        var bytes = new byte[length];
        System.arraycopy(header, 0, bytes, 0, header.length);
        return bytes;
    }

    private static byte[] pngHeader(int width, int height) {
        var bytes = new byte[24];
        System.arraycopy(PNG_MAGIC, 0, bytes, 0, PNG_MAGIC.length);
        bytes[12] = 'I';
        bytes[13] = 'H';
        bytes[14] = 'D';
        bytes[15] = 'R';
        bytes[16] = (byte) (width >> 24);
        bytes[17] = (byte) (width >> 16);
        bytes[18] = (byte) (width >> 8);
        bytes[19] = (byte) width;
        bytes[20] = (byte) (height >> 24);
        bytes[21] = (byte) (height >> 16);
        bytes[22] = (byte) (height >> 8);
        bytes[23] = (byte) height;
        return bytes;
    }

    private static byte[] jpegHeader(int width, int height) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD8});
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xE0, 0x00, 0x10});
        out.writeBytes(new byte[14]);
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xC0, 0x00, 0x11, 0x08});
        out.writeBytes(new byte[]{(byte) (height >> 8), (byte) height, (byte) (width >> 8), (byte) width});
        return out.toByteArray();
    }

    private static byte[] gifHeader(int width, int height) {
        var bytes = new byte[10];
        bytes[0] = 'G';
        bytes[1] = 'I';
        bytes[2] = 'F';
        bytes[3] = '8';
        bytes[4] = '9';
        bytes[5] = 'a';
        bytes[6] = (byte) width;
        bytes[7] = (byte) (width >> 8);
        bytes[8] = (byte) height;
        bytes[9] = (byte) (height >> 8);
        return bytes;
    }

    @Test
    void readsPngDimensions() {
        var dimensions = FfmpegImageShrinker.dimensions(pngHeader(2048, 1024), "png");

        assertNotNull(dimensions);
        assertEquals(2048, dimensions.width());
        assertEquals(1024, dimensions.height());
    }

    @Test
    void readsJpegDimensions() {
        var dimensions = FfmpegImageShrinker.dimensions(jpegHeader(3024, 1964), "jpeg");

        assertNotNull(dimensions);
        assertEquals(3024, dimensions.width());
        assertEquals(1964, dimensions.height());
    }

    @Test
    void readsGifDimensions() {
        var dimensions = FfmpegImageShrinker.dimensions(gifHeader(640, 480), "gif");

        assertNotNull(dimensions);
        assertEquals(640, dimensions.width());
        assertEquals(480, dimensions.height());
    }

    @Test
    void ignoresFormatsWithoutHeaderParsing() {
        assertNull(FfmpegImageShrinker.dimensions(new byte[24], "webp"));
        assertNull(FfmpegImageShrinker.dimensions(null, "png"));
        assertNull(FfmpegImageShrinker.dimensions(new byte[10], "png"));
    }

    @Test
    void keepsImageWithinBudgetUntouched() {
        var payload = padded(pngHeader(1024, 768), 64 * 1024);

        assertNull(FfmpegImageShrinker.targetSize(payload, "png"));
    }

    @Test
    void downscalesImageAboveMaxEdge() {
        var size = FfmpegImageShrinker.targetSize(padded(pngHeader(4096, 2048), 1024), "png");

        assertNotNull(size);
        assertEquals(1568, size.width());
        assertEquals(784, size.height());
    }

    @Test
    void downscalesImageAboveBudgetEvenWhenTheEdgeFits() {
        var size = FfmpegImageShrinker.targetSize(padded(pngHeader(1500, 1000), (int) ImageDownscaler.MAX_BYTES + 1), "png");

        assertNotNull(size);
        assertEquals(1500, size.width());
        assertEquals(1000, size.height());
    }

    @Test
    void ignoresImageWithUnknownDimensions() {
        assertNull(FfmpegImageShrinker.targetSize(new byte[2_000_000], "webp"));
    }

    @Test
    void keepsPayloadWhenFfmpegIsUnavailable() {
        var resolutions = new AtomicInteger();
        var shrinker = new FfmpegImageShrinker(() -> {
            resolutions.incrementAndGet();
            throw new IllegalStateException("ffmpeg is not installed");
        });
        var oversized = padded(pngHeader(2048, 2048), 2_000_000);
        var base64 = Base64.getEncoder().encodeToString(oversized);

        assertNull(shrinker.shrinkToJpeg(base64));
        assertNull(shrinker.shrinkToJpeg(base64));
        assertEquals(1, resolutions.get(), "ffmpeg is resolved once per process");
    }

    @Test
    void doesNotResolveFfmpegForAnImageWithinBudget() {
        var resolutions = new AtomicInteger();
        var shrinker = new FfmpegImageShrinker(() -> {
            resolutions.incrementAndGet();
            return Path.of("missing-ffmpeg");
        });

        assertNull(shrinker.shrinkToJpeg(Base64.getEncoder().encodeToString(padded(pngHeader(800, 600), 4096))));
        assertEquals(0, resolutions.get(), "an image within the budget is never re-encoded");
    }

    @Test
    void returnsNullForAnUndecodablePayload() {
        var shrinker = new FfmpegImageShrinker(() -> Path.of("missing-ffmpeg"));

        assertNull(shrinker.shrinkToJpeg("not-base64!!"));
        assertNull(shrinker.shrinkToJpeg(""));
        assertNull(shrinker.shrinkToJpeg(null));
    }

    @Test
    void shrinksRealImageWithFfmpeg() throws Exception {
        var ffmpeg = ffmpeg();
        assumeTrue(ffmpeg != null, "CORE_AI_FFMPEG is not set");
        var directory = Files.createTempDirectory("ffmpeg-shrink-test-");
        try {
            var source = directory.resolve("source.png");
            runFfmpeg(ffmpeg, "-f", "lavfi", "-i", "testsrc=size=2048x2048:duration=1", "-frames:v", "1", source.toString());
            var base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(source));

            var shrunk = new FfmpegImageShrinker(() -> ffmpeg).shrinkToJpeg(base64);

            assertNotNull(shrunk);
            var bytes = Base64.getDecoder().decode(shrunk);
            assertEquals(0xFF, bytes[0] & 0xFF, "jpeg magic");
            assertEquals(0xD8, bytes[1] & 0xFF, "jpeg magic");
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            assertEquals(1568, Math.max(image.getWidth(), image.getHeight()));
        } finally {
            SystemUtil.deleteDirectory(directory);
        }
    }

    @Test
    void flattensTransparencyOntoWhite() throws Exception {
        var ffmpeg = ffmpeg();
        assumeTrue(ffmpeg != null, "CORE_AI_FFMPEG is not set");
        var directory = Files.createTempDirectory("ffmpeg-alpha-test-");
        try {
            var source = directory.resolve("alpha.png");
            runFfmpeg(ffmpeg, "-f", "lavfi", "-i", "color=c=black@0.0:s=2048x1200,format=rgba",
                    "-f", "lavfi", "-i", "color=c=red:s=1024x1200,format=rgba",
                    "-filter_complex", "[0][1]overlay=1024:0", "-frames:v", "1", source.toString());
            var base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(source));

            var shrunk = new FfmpegImageShrinker(() -> ffmpeg).shrinkToJpeg(base64);

            assertNotNull(shrunk);
            var image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(shrunk)));
            var transparentArea = image.getRGB(image.getWidth() / 8, image.getHeight() / 2);
            assertTrue((transparentArea & 0xFF) > 240, "blue channel of the transparent area is white");
            assertTrue((transparentArea >> 16 & 0xFF) > 240, "red channel of the transparent area is white");
            var content = image.getRGB(image.getWidth() * 3 / 4, image.getHeight() / 2);
            assertTrue((content >> 16 & 0xFF) > 200, "content stays red");
            assertTrue((content & 0xFF) < 60, "content stays red");
        } finally {
            SystemUtil.deleteDirectory(directory);
        }
    }
}
