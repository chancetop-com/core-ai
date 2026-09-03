package ai.core.cli.upgrade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpgradeDownloaderTest {

    @TempDir
    Path tempDir;

    @Test
    void extractArchiveFromZip() throws Exception {
        byte[] payload = "binary-content".getBytes(StandardCharsets.UTF_8);
        Path archive = tempDir.resolve("core-ai-cli-windows.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(archive))) {
            out.putNextEntry(new ZipEntry("core-ai-cli-" + UpgradeDownloader.detectPlatformSuffix()));
            out.write(payload);
            out.closeEntry();
        }

        Path targetDir = tempDir.resolve("install");
        Files.createDirectories(targetDir);
        Path binary = UpgradeDownloader.extractArchive(archive, targetDir, "1.2.3");

        assertEquals(targetDir.resolve(UpgradeDownloader.getBinaryFileName("1.2.3")), binary);
        assertArrayEquals(payload, Files.readAllBytes(binary));
        assertFalse(Files.exists(targetDir.resolve(".upgrade-1.2.3")));
    }

    @Test
    void extractArchiveFromTarGz() throws Exception {
        String innerName = "core-ai-cli-" + UpgradeDownloader.detectPlatformSuffix();
        Path source = tempDir.resolve(innerName);
        byte[] payload = "tar-binary-content".getBytes(StandardCharsets.UTF_8);
        Files.write(source, payload);

        Path archive = tempDir.resolve("core-ai-cli-linux.tar.gz");
        var process = new ProcessBuilder("tar", "-czf", archive.toString(), "-C", tempDir.toString(), innerName)
                .redirectErrorStream(true)
                .start();
        assertEquals(0, process.waitFor(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        Files.delete(source);

        Path targetDir = tempDir.resolve("install");
        Files.createDirectories(targetDir);
        Path binary = UpgradeDownloader.extractArchive(archive, targetDir, "1.2.3");

        assertEquals(targetDir.resolve(UpgradeDownloader.getBinaryFileName("1.2.3")), binary);
        assertArrayEquals(payload, Files.readAllBytes(binary));
        assertFalse(Files.exists(targetDir.resolve(".upgrade-1.2.3")));
    }

    @Test
    void extractArchiveFailsWhenBinaryMissing() throws Exception {
        Path archive = tempDir.resolve("core-ai-cli-windows.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(archive))) {
            out.putNextEntry(new ZipEntry("unexpected.txt"));
            out.write("x".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        Path targetDir = tempDir.resolve("install");
        Files.createDirectories(targetDir);

        assertThrows(IOException.class, () -> UpgradeDownloader.extractArchive(archive, targetDir, "1.2.3"));
        assertFalse(Files.exists(targetDir.resolve(".upgrade-1.2.3")));
    }
}
