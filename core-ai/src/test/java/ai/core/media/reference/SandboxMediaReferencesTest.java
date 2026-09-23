package ai.core.media.reference;

import ai.core.agent.ExecutionContext;
import ai.core.sandbox.Sandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * @author stephen
 */
class SandboxMediaReferencesTest {
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final ExecutionContext CONTEXT = ExecutionContext.builder().build();

    /** A model writes a path into JSON with the separators the OS accepts; forward slashes need no escaping. */
    private static String jsonPath(Path file) {
        return file.toString().replace('\\', '/');
    }

    @Test
    void readsAnAbsolutePathOnThisMachineWhenTheSessionHasNoSandbox(@TempDir Path dir) throws IOException {
        var file = dir.resolve("plate.png");
        Files.write(file, PNG_MAGIC);

        var expanded = SandboxMediaReferences.expand(
                "[{\"sandbox_path\":\"" + jsonPath(file) + "\",\"name\":\"plate\",\"role\":\"subject\"}]", "input_images", CONTEXT);

        var expected = "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG_MAGIC);
        assertTrue(expanded.contains("\"b64Json\":\"" + expected + "\""), expanded);
        assertTrue(expanded.contains("\"name\":\"plate\""), "the name the prompt addresses must survive: " + expanded);
        assertTrue(expanded.contains("\"role\":\"subject\""), expanded);
    }

    @Test
    void readsABarePathOnThisMachineWhenTheSessionHasNoSandbox(@TempDir Path dir) throws IOException {
        var file = dir.resolve("frame.jpg");
        var content = "hello".getBytes(StandardCharsets.UTF_8);
        Files.write(file, content);

        var expanded = SandboxMediaReferences.expand(jsonPath(file), "input_images", CONTEXT);

        assertEquals("{\"b64Json\":\"data:image/jpeg;base64," + Base64.getEncoder().encodeToString(content) + "\"}",
                expanded, "the extension is the only type a cut frame reveals");
    }

    @Test
    void reportsAnUnreadablePathWhenTheSessionHasNoSandbox(@TempDir Path dir) {
        var missing = jsonPath(dir.resolve("never-written.png"));

        var failure = assertThrows(IllegalArgumentException.class, () -> SandboxMediaReferences.expand(
                "[{\"sandbox_path\":\"" + missing + "\"}]", "input_images", CONTEXT));

        assertTrue(failure.getMessage().contains("no sandbox"), failure.getMessage());
        assertTrue(failure.getMessage().contains("media_id"), "the forms that do work must be named: " + failure.getMessage());
    }

    @Test
    void leavesUrlReferencesUntouchedWhenTheSessionHasNoSandbox() {
        var value = "[{\"url\":\"https://example.com/a.png\",\"role\":\"subject\"}]";

        assertEquals(value, SandboxMediaReferences.expand(value, "input_images", CONTEXT));
    }

    @Test
    void stillReadsThroughTheSandboxWhenThereIsOne() {
        var sandbox = mock(Sandbox.class);

        var failure = assertThrows(IllegalArgumentException.class, () -> SandboxMediaReferences.expand(
                "[{\"sandbox_path\":\"/etc/hosts\"}]", "input_images", ExecutionContext.builder().sandbox(sandbox).build()));

        assertTrue(failure.getMessage().contains("/tmp/"), "a sandbox keeps its root restriction: " + failure.getMessage());
    }
}
