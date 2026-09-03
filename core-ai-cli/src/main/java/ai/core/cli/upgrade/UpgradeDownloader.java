package ai.core.cli.upgrade;

import ai.core.utils.SystemUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.Serial;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;

/**
 * @author stephen
 */
public final class UpgradeDownloader {

    private static final Logger LOGGER = LoggerFactory.getLogger(UpgradeDownloader.class);

    private static final String RELEASE_BASE_URL = "https://github.com/chancetop-com/core-ai/releases/download/v%s/%s";

    private static final Path DEFAULT_INSTALL_DIR = Path.of(System.getProperty("user.home"), ".core-ai", "bin");

    public static String detectPlatformSuffix() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows.exe";
        if (os.contains("mac") || os.contains("darwin")) return "darwin";
        return "linux";
    }

    public static String archiveFileName() {
        if (isWindows()) return "core-ai-cli-windows.zip";
        return "core-ai-cli-" + detectPlatformSuffix() + ".tar.gz";
    }

    private static String archiveInnerBinaryName() {
        return "core-ai-cli-" + detectPlatformSuffix();
    }

    public static String getBinaryFileName(String version) {
        return "core-ai-cli-v" + version + (isWindows() ? ".exe" : "");
    }

    public static Path resolveInstallDir() {
        return DEFAULT_INSTALL_DIR;
    }

    public static Path findCurrentBinary() {
        return ProcessHandle.current().info().command()
                .map(Path::of)
                .filter(Files::exists)
                .orElse(null);
    }

    public static boolean isInPath(Path dir) {
        String dirStr = dir.toAbsolutePath().normalize().toString();
        return Arrays.stream(System.getenv("PATH").split(File.pathSeparator))
                .map(p -> Path.of(p).toAbsolutePath().normalize().toString())
                .anyMatch(p -> p.equalsIgnoreCase(dirStr));
    }

    /**
     * Downloads the release archive (zip on Windows, tar.gz elsewhere), extracts the binary into
     * targetDir as core-ai-cli-v{version}[.exe]. Falls back to the plain binary asset for
     * releases that predate archive packaging.
     */
    public static Path download(String version, Path targetDir) throws UpgradeException {
        try {
            return downloadArchive(version, targetDir);
        } catch (UpgradeException e) {
            return downloadBinary(version, targetDir);
        }
    }

    private static Path downloadArchive(String version, Path targetDir) throws UpgradeException {
        String archiveName = archiveFileName();
        Path archiveFile = targetDir.resolve(archiveName);
        try {
            Files.createDirectories(targetDir);
            SystemUtil.download(String.format(RELEASE_BASE_URL, version, archiveName), archiveFile.toString());
            Path binary = extractArchive(archiveFile, targetDir, version);
            deleteQuietly(archiveFile);
            return binary;
        } catch (IOException | URISyntaxException e) {
            deleteQuietly(archiveFile);
            throw new UpgradeException("Archive download failed: " + e.getMessage(), e);
        }
    }

    static Path extractArchive(Path archiveFile, Path targetDir, String version) throws IOException {
        Path extractDir = targetDir.resolve(".upgrade-" + version);
        Files.createDirectories(extractDir);
        try {
            return extractToTarget(archiveFile, extractDir, targetDir, version);
        } catch (IOException e) {
            deleteQuietly(extractDir);
            throw e;
        }
    }

    private static Path extractToTarget(Path archiveFile, Path extractDir, Path targetDir, String version) throws IOException {
        if (archiveFile.toString().endsWith(".zip")) {
            SystemUtil.extractZip(archiveFile, extractDir);
        } else {
            unTar(archiveFile, extractDir);
        }
        Path extracted = extractDir.resolve(archiveInnerBinaryName());
        if (!Files.isRegularFile(extracted)) {
            throw new IOException("Archive does not contain " + archiveInnerBinaryName());
        }
        Path target = targetDir.resolve(getBinaryFileName(version));
        Files.move(extracted, target, StandardCopyOption.REPLACE_EXISTING);
        if (!isWindows()) {
            target.toFile().setExecutable(true, false);
        }
        SystemUtil.deleteDirectory(extractDir);
        return target;
    }

    private static void unTar(Path archiveFile, Path extractDir) throws IOException {
        try {
            SystemUtil.extractTarGz(archiveFile, extractDir);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Tar extraction interrupted", e);
        }
    }

    private static Path downloadBinary(String version, Path targetDir) throws UpgradeException {
        String url = String.format(RELEASE_BASE_URL, version, archiveInnerBinaryName());
        Path targetFile = targetDir.resolve(getBinaryFileName(version));
        try {
            Files.createDirectories(targetDir);
            File downloaded = SystemUtil.download(url, targetFile.toString());
            if (!isWindows()) {
                downloaded.setExecutable(true, false);
            }
            return downloaded.toPath();
        } catch (IOException | URISyntaxException e) {
            throw new UpgradeException("Download failed: " + e.getMessage(), e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            SystemUtil.deleteDirectory(path);
        } catch (IOException e) {
            // a leftover archive or extraction dir is harmless; the extracted binary is authoritative
            LOGGER.warn("Failed to delete {}: {}", path, e);
        }
    }

    public static Path tryReplaceCurrent(Path downloaded, Path currentBinary) throws UpgradeException {
        if (currentBinary == null || !Files.exists(currentBinary)) {
            return downloaded;
        }
        try {
            Files.move(downloaded, currentBinary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return currentBinary;
        } catch (IOException e) {
            return scheduleReplaceOnExit(downloaded, currentBinary);
        }
    }

    /**
     * When the running binary cannot be replaced in-place (e.g. Windows locks running .exe),
     * save the new binary as .new alongside the current one and spawn a detached script
     * that will replace it after this process exits.
     * Returns currentBinary on success (replacement scheduled), newFile on failure (manual fallback).
     */
    static Path scheduleReplaceOnExit(Path downloaded, Path currentBinary) throws UpgradeException {
        Path newFile = currentBinary.resolveSibling(currentBinary.getFileName() + ".new");
        try {
            Files.move(downloaded, newFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UpgradeException("Cannot save new binary: " + e.getMessage(), e);
        }
        try {
            spawnUpgradeScript(newFile, currentBinary);
        } catch (IOException e) {
            return newFile;
        }
        return currentBinary;
    }

    /**
     * Returns true if an upgrade script is pending (scheduled but not yet executed).
     */
    public static boolean isUpgradeScheduled(Path currentBinary) {
        if (currentBinary == null) return false;
        Path newFile = currentBinary.resolveSibling(currentBinary.getFileName() + ".new");
        return Files.exists(newFile);
    }

    private static void spawnUpgradeScript(Path newFile, Path targetFile) throws IOException {
        if (isWindows()) {
            spawnWindowsUpgradeScript(newFile, targetFile);
        } else {
            spawnUnixUpgradeScript(newFile, targetFile);
        }
    }

    private static void spawnWindowsUpgradeScript(Path newFile, Path targetFile) throws IOException {
        Path script = targetFile.resolveSibling("core-ai-upgrade.ps1");
        String scriptContent = String.format("$ErrorActionPreference = 'Stop'%n"
                + "Start-Sleep -Seconds 1%n"
                + "Move-Item -Force -LiteralPath '%s' -Destination '%s'%n"
                + "Remove-Item -Force -LiteralPath '%s'%n", newFile, targetFile, script);
        Files.writeString(script, scriptContent);
        new ProcessBuilder("cmd", "/c", "start", "/min", "", "powershell.exe", "-ExecutionPolicy", "Bypass", "-File", script.toAbsolutePath().toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static void spawnUnixUpgradeScript(Path newFile, Path targetFile) throws IOException {
        Path script = targetFile.resolveSibling("core-ai-upgrade.sh");
        String scriptContent = String.format("#!/bin/sh%n"
                + "while kill -0 %d 2>/dev/null; do sleep 0.5; done%n"
                + "sleep 0.5%n"
                + "mv '%s' '%s'%n"
                + "chmod +x '%s'%n"
                + "rm -f '%s'%n", ProcessHandle.current().pid(), newFile, targetFile, targetFile, script);
        Files.writeString(script, scriptContent);
        script.toFile().setExecutable(true, false);
        new ProcessBuilder("sh", "-c", script.toAbsolutePath().toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    public static String pathSetupInstructions(Path dir) {
        if (isWindows()) {
            return "To add to PATH (Windows PowerShell, run as admin):\n"
                    + "  [Environment]::SetEnvironmentVariable('PATH', $env:PATH + ';" + dir + "', 'User')";
        }
        String shellRc = shellRcFile();
        return "To add to PATH, add this line to ~/" + shellRc + ":\n"
                + "  export PATH=\"$PATH:" + dir + "\"";
    }

    private static String shellRcFile() {
        String shell = System.getenv().getOrDefault("SHELL", "");
        if (shell.contains("zsh")) return ".zshrc";
        if (shell.contains("bash")) return ".bashrc";
        return ".profile";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    public static final class UpgradeException extends Exception {
        @Serial
        private static final long serialVersionUID = 1414888476067623615L;

        public UpgradeException(String message) {
            super(message);
        }

        public UpgradeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
