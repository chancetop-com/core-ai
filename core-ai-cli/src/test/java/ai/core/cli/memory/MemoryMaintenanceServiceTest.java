package ai.core.cli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryMaintenanceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void rebuildsTheIndexAndRunsAtMostOncePerDay() throws IOException {
        var workspace = tempDir.resolve("ws");
        var page = workspace.resolve(".core-ai/knowledge/project/page.md");
        var parent = page.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(page, "# Page\n");
        var service = new MemoryMaintenanceService(workspace,
                new MdMemoryProvider(workspace), () -> null);

        var result = service.run();

        assertNotNull(result);
        assertTrue(result.changed());
        assertTrue(Files.readString(workspace.resolve(".core-ai/knowledge/MEMORY.md"))
                .contains("project/page.md"));
        assertFalse(service.runDailyIfDue(), "a manual run already counted for today");
    }
}
