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
        return paths(null);
    }

    private List<String> paths(String sessionId) {
        var params = JsonUtil.OBJECT_MAPPER.createObjectNode();
        if (sessionId != null) {
            params.put("sessionId", sessionId);
        }
        var files = LocalArtifacts.list(workspace, params).get("files");
        var paths = new ArrayList<String>();
        files.forEach(file -> paths.add(file.get("path").asText()));
        return paths;
    }

    private List<String> sortedPaths(String sessionId) {
        var paths = paths(sessionId);
        paths.sort(String::compareTo);
        return paths;
    }

    @Test
    void mergesRegisteredFilesAndSkipsStaleOrDuplicateEntries() throws Exception {
        Files.createDirectories(workspace.resolve(".core-ai/media/images"));
        Files.writeString(workspace.resolve(".core-ai/media/images/image-a.png"), "png");
        Files.createDirectories(workspace.resolve("reports"));
        Files.writeString(workspace.resolve("reports/weekly.html"), "<h1>w</h1>");

        LocalArtifactManifest.append(workspace, "reports/weekly.html", null);
        LocalArtifactManifest.append(workspace, "reports/missing.pdf", null);
        LocalArtifactManifest.append(workspace, "reports/weekly.html", null);

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
        LocalArtifactManifest.append(workspace, ".core-ai/tasks/report.md", null);

        assertEquals(List.of(".core-ai/tasks/report.md"), paths());
    }

    @Test
    void sessionFilterReturnsOnlyThatSessionsArtifacts() throws Exception {
        Files.createDirectories(workspace.resolve(".core-ai/media/images"));
        Files.writeString(workspace.resolve(".core-ai/media/images/image-a.png"), "a");
        Files.writeString(workspace.resolve(".core-ai/media/images/image-b.png"), "b");
        Files.createDirectories(workspace.resolve("reports"));
        Files.writeString(workspace.resolve("reports/a.md"), "a");
        Files.writeString(workspace.resolve("reports/b.md"), "b");
        LocalArtifactManifest.append(workspace, ".core-ai/media/images/image-a.png", "s-1");
        LocalArtifactManifest.append(workspace, "reports/a.md", "s-1");
        LocalArtifactManifest.append(workspace, ".core-ai/media/images/image-b.png", "s-2");
        LocalArtifactManifest.append(workspace, "reports/b.md", "s-2");

        assertEquals(List.of(".core-ai/media/images/image-a.png", "reports/a.md"), sortedPaths("s-1"));
        assertEquals(List.of(".core-ai/media/images/image-b.png", "reports/b.md"), sortedPaths("s-2"));
        assertEquals(4, paths().size());
    }

    @Test
    void unattributedWorkspaceFilesStayOutOfTheSessionView() throws Exception {
        Files.createDirectories(workspace.resolve(".core-ai/tasks"));
        Files.writeString(workspace.resolve(".core-ai/tasks/subagent.md"), "x");
        Files.createDirectories(workspace.resolve(".core-ai/media/images"));
        Files.writeString(workspace.resolve(".core-ai/media/images/legacy.png"), "x");

        assertEquals(2, paths().size());
        assertTrue(paths("s-1").isEmpty());
    }

    @Test
    void kindFollowsTheFileExtension() {
        assertEquals("image", LocalArtifacts.kindOf("chart.PNG"));
        assertEquals("video", LocalArtifacts.kindOf("clip.mp4"));
        assertEquals("file", LocalArtifacts.kindOf("weekly.html"));
    }
}
