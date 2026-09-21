package ai.core.cli.hub.dataset;

import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubExitCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author stephen
 */
class DatasetDataInputTest {
    private final DatasetDataInput input = new DatasetDataInput();

    @TempDir
    Path tempDir;

    @Test
    void inlineDataIsUsedAsIs() {
        assertEquals("{\"a\":1}", input.read("{\"a\":1}", null));
    }

    @Test
    void dataFileIsRead() throws IOException {
        var file = tempDir.resolve("payload.json");
        Files.writeString(file, "{\"b\":2}", StandardCharsets.UTF_8);

        assertEquals("{\"b\":2}", input.read(null, file));
    }

    @Test
    void dashReadsStdin() {
        withStdin("{\"c\":3}", () -> assertEquals("{\"c\":3}", input.read("-", null)));
    }

    @Test
    void dashDataFileReadsStdin() {
        withStdin("{\"d\":4}", () -> assertEquals("{\"d\":4}", input.read(null, Path.of("-"))));
    }

    @Test
    void bothSourcesAreRejected() {
        var error = assertThrows(HubCliError.class, () -> input.read("{\"a\":1}", Path.of("payload.json")));

        assertEquals(HubExitCodes.USAGE, error.exitCode);
        assertEquals("pass either --data or --data-file, not both", error.getMessage());
    }

    @Test
    void neitherSourceIsUsageError() {
        var error = assertThrows(HubCliError.class, () -> input.read(null, null));

        assertEquals(HubExitCodes.USAGE, error.exitCode);
        assertEquals("missing data: pass --data <json> or --data-file <path|->", error.getMessage());
    }

    @Test
    void unreadableFileIsUsageError() {
        var error = assertThrows(HubCliError.class, () -> input.read(null, tempDir.resolve("missing.json")));

        assertEquals(HubExitCodes.USAGE, error.exitCode);
    }

    private void withStdin(String content, Runnable assertion) {
        var original = System.in;
        System.setIn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        try {
            assertion.run();
        } finally {
            System.setIn(original);
        }
    }
}
