package ai.core.vender.vendors;

import ai.core.utils.Platform;
import ai.core.utils.SystemUtil;
import ai.core.vender.Vendor;
import ai.core.vender.VendorException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author stephen
 */
public class RipgrepVendor extends Vendor {
    private static final String VERSION = "14.1.1";
    private static final String GITHUB_RELEASE_URL = "https://github.com/BurntSushi/ripgrep/releases/download";

    private Path downloadedArchive;

    public RipgrepVendor(Path customVendorHome) {
        super(customVendorHome);
    }

    @Override
    public String getVendorName() {
        return "ripgrep";
    }

    @Override
    public String getVersion() {
        return VERSION;
    }

    @Override
    protected boolean isInstalled() {
        try {
            var execPath = getExecutablePathInternal();
            return Files.exists(execPath) && Files.isExecutable(execPath);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void download() throws Exception {
        var platform = SystemUtil.detectPlatform();
        var downloadUrl = buildDownloadUrl(platform);

        var fileName = getArchiveFileName(platform);
        downloadedArchive = vendorHome.resolve(fileName);

        var file = SystemUtil.download(downloadUrl, downloadedArchive.toString());
        logger.info("Downloaded ripgrep archive to: {}", file.getAbsolutePath());
    }

    @Override
    protected void install() throws Exception {
        var platform = SystemUtil.detectPlatform();
        logger.info("Installing ripgrep from: {}", downloadedArchive);

        if (platform.isWindows()) {
            SystemUtil.extractZip(downloadedArchive, vendorHome);
        } else {
            SystemUtil.extractTarGz(downloadedArchive, vendorHome);
        }

        // Set executable permissions on Unix-like systems
        if (!platform.isWindows()) {
            var execPath = getExecutablePathInternal();
            SystemUtil.setExecutablePermissions(execPath);
        }

        logger.info("Ripgrep installed successfully to: {}", vendorHome);
    }

    @Override
    protected void verify() {
        var execPath = getExecutablePathInternal();

        if (!Files.exists(execPath)) {
            throw new VendorException("Ripgrep executable not found at: " + execPath);
        }

        if (!Files.isExecutable(execPath)) {
            throw new VendorException("Ripgrep executable is not executable: " + execPath);
        }

        // Try running ripgrep --version to verify it works
        try {
            var process = new ProcessBuilder(execPath.toString(), "--version")
                .redirectErrorStream(true)
                .start();

            var exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new VendorException("Ripgrep verification failed with exit code: " + exitCode);
            }

            logger.info("Ripgrep verification successful");
        } catch (IOException | InterruptedException e) {
            throw new VendorException("Failed to verify ripgrep installation", e);
        }
    }

    @Override
    protected Path getExecutablePathInternal() {
        var platform = SystemUtil.detectPlatform();
        var dirName = getExtractedDirName(platform);
        var execName = platform.isWindows() ? "rg.exe" : "rg";

        return vendorHome.resolve(dirName).resolve(execName);
    }

    private String buildDownloadUrl(Platform platform) {
        var fileName = getArchiveFileName(platform);
        return String.format("%s/%s/%s", GITHUB_RELEASE_URL, VERSION, fileName);
    }

    private String getArchiveFileName(Platform platform) {
        return switch (platform) {
            case WINDOWS_X64 -> String.format("ripgrep-%s-x86_64-pc-windows-msvc.zip", VERSION);
            case WINDOWS_X86 -> String.format("ripgrep-%s-i686-pc-windows-msvc.zip", VERSION);
            case LINUX_X64 -> String.format("ripgrep-%s-x86_64-unknown-linux-musl.tar.gz", VERSION);
            case LINUX_ARM64 -> String.format("ripgrep-%s-aarch64-unknown-linux-gnu.tar.gz", VERSION);
            case MACOS_X64 -> String.format("ripgrep-%s-x86_64-apple-darwin.tar.gz", VERSION);
            case MACOS_ARM64 -> String.format("ripgrep-%s-aarch64-apple-darwin.tar.gz", VERSION);
            case UNKNOWN -> throw new IllegalArgumentException("Unsupported platform for ripgrep");
        };
    }

    private String getExtractedDirName(Platform platform) {
        return switch (platform) {
            case WINDOWS_X64 -> String.format("ripgrep-%s-x86_64-pc-windows-msvc", VERSION);
            case WINDOWS_X86 -> String.format("ripgrep-%s-i686-pc-windows-msvc", VERSION);
            case LINUX_X64 -> String.format("ripgrep-%s-x86_64-unknown-linux-musl", VERSION);
            case LINUX_ARM64 -> String.format("ripgrep-%s-aarch64-unknown-linux-gnu", VERSION);
            case MACOS_X64 -> String.format("ripgrep-%s-x86_64-apple-darwin", VERSION);
            case MACOS_ARM64 -> String.format("ripgrep-%s-aarch64-apple-darwin", VERSION);
            case UNKNOWN -> throw new IllegalArgumentException("Unsupported platform for ripgrep");
        };
    }
}
