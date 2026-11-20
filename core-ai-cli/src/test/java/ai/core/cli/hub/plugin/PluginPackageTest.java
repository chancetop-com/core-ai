package ai.core.cli.hub.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class PluginPackageTest {
    @TempDir
    Path tempDir;

    @Test
    void installsAValidPackage() throws IOException {
        var target = tempDir.resolve("plugin");
        var result = PluginPackage.install(zip("""
                {"name":"demo","version":"1.0.0"}
                """, "plugin.json", "pages/index.html", "<html></html>"), target);

        assertEquals(2, result.files());
        assertTrue(result.manifest().contains("\"demo\""));
        assertTrue(Files.exists(target.resolve("pages/index.html")));
    }

    @Test
    void findsALegacyManifest() throws IOException {
        var manifest = PluginPackage.readManifest(zip("""
                {"name":"legacy","version":"0.1.0"}
                """, ".claude-plugin/plugin.json", "README.md", "hi"));
        assertTrue(manifest.contains("legacy"));
    }

    @Test
    void rejectsPathTraversal() throws IOException {
        var archive = zipWithoutValidation("../escape.txt", "boom");
        assertThrows(IOException.class, () -> PluginPackage.install(archive, tempDir.resolve("plugin")));
    }

    @Test
    void rejectsAPackageWithoutAManifest() throws IOException {
        var archive = zipWithoutValidation("README.md", "hi");
        assertThrows(IOException.class, () -> PluginPackage.install(archive, tempDir.resolve("plugin")));
    }

    private byte[] zip(String manifest, String manifestPath, String extraPath, String extraContent) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(manifestPath));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(extraPath));
            zip.write(extraContent.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private byte[] zipWithoutValidation(String path, String content) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(path));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
