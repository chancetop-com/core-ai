package ai.core.cli.upgrade;

import ai.core.utils.SystemUtil;

import java.io.File;
import java.io.IOException;
import java.io.Serial;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * @author stephen
 */
public final class UpgradeDownloader {

    private static final String DOWNLOAD_URL_TEMPLATE = "https://github.com/chancetop-com/core-ai/releases/download/v%s/core-ai-cli-%s";

    private static final Path DEFAULT_INSTALL_DIR = Path.of(System.getProperty("user.home"), ".core-ai", "bin");

    public static String detectPlatformSuffix() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "windows.exe";
        if (os.contains("mac") || os.contains("darwin")) return "darwin";
        return "linux";
    }

    public static String getBinaryFileName(String version) {
        return "core-ai-cli-v" + version + (isWindows() ? ".exe" : "");
    }

    public static Path resolveInstallDir() {
        Path current = findCurrentBinary();
        if (current != null) {
            return current.getParent();
        }
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
        return Arrays.stream(System.getenv("PATH").split(java.io.File.pathSeparator))
                .map(p -> Path.of(p).toAbsolutePath().normalize().toString())
                .anyMatch(p -> p.equalsIgnoreCase(dirStr));
    }

    public static Path download(String version, Path targetDir) throws UpgradeException {
        String platformSuffix = detectPlatformSuffix();
        String url = String.format(DOWNLOAD_URL_TEMPLATE, version, platformSuffix);
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

    public static Path tryReplaceCurrent(Path downloaded, Path currentBinary) throws UpgradeException {
        if (currentBinary == null || !Files.exists(currentBinary)) {
            return downloaded;
        }
        try {
            Files.move(downloaded, currentBinary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return currentBinary;
        } catch (IOException e) {
            Path newFile = currentBinary.resolveSibling(currentBinary.getFileName() + ".new");
            try {
                Files.move(downloaded, newFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return newFile;
            } catch (IOException e2) {
                throw new UpgradeException("Cannot install: " + e2.getMessage(), e2);
            }
        }
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
        return System.getProperty("os.name").toLowerCase().contains("win");
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
