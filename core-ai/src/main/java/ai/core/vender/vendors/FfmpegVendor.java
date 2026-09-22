package ai.core.vender.vendors;

import ai.core.utils.Platform;
import ai.core.utils.ShellUtil;
import ai.core.utils.SystemUtil;
import ai.core.vender.Vendor;
import ai.core.vender.VendorException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Vendored ffmpeg, the inline image encoder of hosts without the AWT image pipeline (the GraalVM native CLI).
 * A system ffmpeg on the PATH always wins; otherwise a single static binary is downloaded from the
 * ffmpeg-static release (the gzip variant, ~25-30MB instead of the ~80MB raw binary) and the sha256 pinned
 * in this class is verified before it is unpacked, so a broken or substituted download never gets executed.
 * Only still image transcoding is used today, the same binary is what video/audio work on the client side
 * will need later.
 *
 * @author stephen
 */
public class FfmpegVendor extends Vendor {
    private static final String VERSION = "b6.1.1";
    private static final String RELEASE_PATH = "eugeneware/ffmpeg-static/releases/download";
    /** gh proxy keeps the download usable from networks that cannot reach github directly */
    private static final String MIRROR_BASE_URL = "https://gh-proxy.org/https://github.com/" + RELEASE_PATH;
    private static final String DIRECT_BASE_URL = "https://github.com/" + RELEASE_PATH;
    private static final Asset WINDOWS_X64_ASSET = new Asset("ffmpeg-win32-x64.gz",
            "8883a3dffbd0a16cf4ef95206ea05283f78908dbfb118f73c83f4951dcc06d77");
    private static final Asset LINUX_X64_ASSET = new Asset("ffmpeg-linux-x64.gz",
            "bfe8a8fc511530457b528c48d77b5737527b504a3797a9bc4866aeca69c2dffa");
    private static final Asset LINUX_ARM64_ASSET = new Asset("ffmpeg-linux-arm64.gz",
            "754a678672298bc68156adff58aa7385a592c2b30b1d0ae8750c45c915c4bac0");
    private static final Asset MACOS_X64_ASSET = new Asset("ffmpeg-darwin-x64.gz",
            "929b375c1182d956c51f7ac25e0b2b0411fb01f6f407aa15c9758efeb4242106");
    private static final Asset MACOS_ARM64_ASSET = new Asset("ffmpeg-darwin-arm64.gz",
            "8923876afa8db5585022d7860ec7e589af192f441c56793971276d450ed3bbfa");
    private static final List<String> BASE_URLS = List.of(MIRROR_BASE_URL, DIRECT_BASE_URL);

    /**
     * ffmpeg-static publishes x64 and arm64 builds only, and {@link Platform} cannot tell the two apart,
     * so the cpu architecture decides which one is picked.
     */
    static Asset assetFor(Platform platform, boolean arm64) {
        return switch (platform) {
            case WINDOWS_X64, WINDOWS_X86 -> WINDOWS_X64_ASSET;
            case MACOS_X64, MACOS_ARM64 -> arm64 ? MACOS_ARM64_ASSET : MACOS_X64_ASSET;
            case LINUX_X64, LINUX_ARM64 -> arm64 ? LINUX_ARM64_ASSET : LINUX_X64_ASSET;
            case UNKNOWN -> throw new VendorException("Unsupported platform for ffmpeg: " + System.getProperty("os.name"));
        };
    }

    private static boolean isWindows() {
        var platform = SystemUtil.detectPlatform();
        return platform == Platform.WINDOWS_X64 || platform == Platform.WINDOWS_X86;
    }

    private static boolean isArm64() {
        var arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm64");
    }

    private static String sha256(Path file) throws IOException {
        var digest = sha256Digest();
        try (var in = Files.newInputStream(file)) {
            var buffer = new byte[8192];
            var read = in.read(buffer);
            while (read > 0) {
                digest.update(buffer, 0, read);
                read = in.read(buffer);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 digest is not available", e);
        }
    }

    private static String readFirstLine(Process process) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.readLine();
        }
    }

    private static String readOutput(Process process) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            var output = new StringBuilder();
            var line = reader.readLine();
            while (line != null) {
                output.append(line).append('\n');
                line = reader.readLine();
            }
            return output.toString();
        }
    }

    private Path downloadedArchive;
    private Path systemFfmpegPath;

    public FfmpegVendor() {
    }

    public FfmpegVendor(Path customVendorHome) {
        super(customVendorHome);
    }

    @Override
    public String getVendorName() {
        return "ffmpeg";
    }

    @Override
    public String getVersion() {
        return VERSION;
    }

    @Override
    protected boolean isInstalled() {
        systemFfmpegPath = resolveSystemFfmpegPath();
        if (systemFfmpegPath != null) {
            logger.debug("Found system ffmpeg at: {}", systemFfmpegPath);
            return true;
        }
        try {
            var executablePath = getExecutablePathInternal();
            return Files.exists(executablePath) && Files.isExecutable(executablePath);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    protected void download() throws Exception {
        var asset = assetFor(SystemUtil.detectPlatform(), isArm64());
        downloadedArchive = vendorHome.resolve(asset.fileName());

        for (var baseUrl : BASE_URLS) {
            var url = baseUrl + "/" + VERSION + "/" + asset.fileName();
            try {
                SystemUtil.download(url, downloadedArchive.toString());
            } catch (IOException | URISyntaxException e) {
                logger.warn("failed to download ffmpeg from {}, error={}", baseUrl, e.getMessage());
                continue;
            }
            var checksum = sha256(downloadedArchive);
            if (asset.sha256().equals(checksum)) {
                logger.debug("downloaded ffmpeg {} from {}", VERSION, baseUrl);
                return;
            }
            logger.warn("ffmpeg archive checksum mismatch, url={}, expected={}, actual={}", url, asset.sha256(), checksum);
        }
        throw new VendorException("Failed to download ffmpeg " + VERSION + ", platform=" + SystemUtil.detectPlatform());
    }

    @Override
    protected void install() throws Exception {
        var executablePath = getExecutablePathInternal();
        try (var in = new GZIPInputStream(Files.newInputStream(downloadedArchive));
             var out = Files.newOutputStream(executablePath)) {
            in.transferTo(out);
        }
        Files.deleteIfExists(downloadedArchive);
        if (!isWindows()) {
            SystemUtil.setExecutablePermissions(executablePath);
        }
        logger.debug("ffmpeg installed at: {}", executablePath);
    }

    @Override
    protected void verify() {
        var executablePath = getExecutablePathInternal();
        if (!Files.exists(executablePath)) {
            throw new VendorException("ffmpeg executable not found at: " + executablePath);
        }
        if (!Files.isExecutable(executablePath)) {
            throw new VendorException("ffmpeg executable is not executable: " + executablePath);
        }
        try {
            var process = new ProcessBuilder(executablePath.toString(), "-hide_banner", "-version")
                    .redirectErrorStream(true)
                    .start();
            var output = readOutput(process);
            var exitCode = process.waitFor();
            if (exitCode != 0 || !output.contains("ffmpeg version")) {
                throw new VendorException("ffmpeg verification failed, exitCode=" + exitCode + ", output=" + output);
            }
            logger.debug("ffmpeg verification successful");
        } catch (IOException e) {
            throw new VendorException("Failed to verify ffmpeg installation", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VendorException("Interrupted while verifying ffmpeg installation", e);
        }
    }

    @Override
    protected Path getExecutablePathInternal() {
        if (systemFfmpegPath != null) {
            return systemFfmpegPath;
        }
        return vendorHome.resolve(isWindows() ? "ffmpeg.exe" : "ffmpeg");
    }

    private Path resolveSystemFfmpegPath() {
        var platform = SystemUtil.detectPlatform();
        if (!ShellUtil.isCommandExists(platform, "ffmpeg")) {
            return null;
        }
        try {
            var whichCommand = isWindows() ? "where.exe" : "which";
            var process = new ProcessBuilder(whichCommand, "ffmpeg").start();
            var firstLine = readFirstLine(process);
            process.waitFor();
            if (firstLine != null) {
                var path = Paths.get(firstLine.trim());
                if (Files.exists(path) && Files.isExecutable(path)) {
                    return path;
                }
            }
        } catch (IOException e) {
            logger.debug("Failed to resolve system ffmpeg path: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    record Asset(String fileName, String sha256) {
    }
}
