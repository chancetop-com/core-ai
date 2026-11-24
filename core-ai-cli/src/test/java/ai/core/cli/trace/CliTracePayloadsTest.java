package ai.core.cli.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The uploader carries local image bytes only for files inside the session media root.
 *
 * @author core-ai
 */
class CliTracePayloadsTest {
    private static CliTraceSpan span(String input) {
        var span = new CliTraceSpan();
        span.traceId = "trace-1";
        span.input = input;
        return span;
    }

    private static String input(Path path, String sha256) {
        return "[{\"role\":\"USER\",\"content\":[{\"type\":\"image_ref\",\"image_ref\":{"
                + "\"id\":\"" + sha256 + "\",\"kind\":\"file\",\"path\":\"" + path.toAbsolutePath().toString().replace("\\", "\\\\") + "\","
                + "\"media_type\":\"image/png\",\"sha256\":\"" + sha256 + "\"}}]}]";
    }

    @TempDir
    Path tempDir;

    @Test
    void uploadsAReferencedImageInsideTheMediaRoot() throws Exception {
        var mediaRoot = tempDir.resolve("media").resolve("session-1");
        var image = mediaRoot.resolve("sha.png");
        Files.createDirectories(mediaRoot);
        Files.write(image, new byte[]{1, 2, 3});
        var span = span(input(image, "sha"));

        var payloads = CliTracePayloads.collect(java.util.List.of(span), mediaRoot);

        assertEquals(1, payloads.size());
        assertEquals("trace-1", payloads.getFirst().traceId);
        assertTrue(payloads.getFirst().dataUrl.startsWith("data:image/png;base64,"));
    }

    @Test
    void refusesAReferenceOutsideTheMediaRoot() throws Exception {
        var mediaRoot = tempDir.resolve("media").resolve("session-1");
        Files.createDirectories(mediaRoot);
        var outside = tempDir.resolve("secret.png");
        Files.write(outside, new byte[]{1, 2, 3});
        var span = span(input(outside, "sha"));

        assertTrue(CliTracePayloads.collect(java.util.List.of(span), mediaRoot).isEmpty());
    }
}
