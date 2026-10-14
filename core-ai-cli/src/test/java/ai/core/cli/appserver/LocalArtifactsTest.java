package ai.core.cli.appserver;

import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class LocalArtifactsTest {
    @TempDir
    Path workspace;

    private List<String> paths() {
        var files = LocalArtifacts.list(workspace, JsonUtil.OBJECT_MAPPER.createObjectNode()).get("files");
        var paths = new ArrayList<String>();
        files.forEach(file -> paths.add(file.get("path").asText()));
        return paths;
    }

    @Test
    void mergesRegisteredFilesAndSkipsStaleOrDuplicateEntries() throws Exception {
        Files.createDirectories(workspace.resolve(".core-ai/media/images"));
        Files.writeString(workspace.resolve(".core-ai/media/images/image-a.png"), "png");
        Files.createDirectories(workspace.resolve("reports"));
        Files.writeString(workspace.resolve("reports/weekly.html"), "<h1>w</h1>");

        LocalArtifactManifest.append(workspace, "reports/weekly.html");
        LocalArtifactManifest.append(workspace, "reports/missing.pdf");
        LocalArtifactManifest.append(workspace, "reports/weekly.html");

        var paths = paths();
        assertTrue(paths.contains("reports/weekly.html"));
        assertTrue(paths.contains(".core-ai/media/images/image-a.png"));
        assertFalse(paths.contains("reports/missing.pdf"));
        assertEquals(2, paths.size());
    }

    @Test
    void registeredFilesAlreadyScannedStaySingle() throws Exception {
        Files.createDirectories(workspace.resolve(".core-ai/tasks"));
        Files.writeString(workspace.resolve(".core-ai/tasks/report.md"), "# r");
        LocalArtifactManifest.append(workspace, ".core-ai/tasks/report.md");

        assertEquals(List.of(".core-ai/tasks/report.md"), paths());
    }

    @Test
    void kindFollowsTheFileExtension() {
        assertEquals("image", LocalArtifacts.kindOf("chart.PNG"));
        assertEquals("video", LocalArtifacts.kindOf("clip.mp4"));
        assertEquals("file", LocalArtifacts.kindOf("weekly.html"));
    }
}
