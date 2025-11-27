package ai.core.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * @author stephen
 */
public class SystemUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(SystemUtil.class);

    public static Platform detectPlatform() {
        var os = System.getProperty("os.name").toLowerCase(Locale.getDefault());
        return Platform.fromName(os);
    }

    public static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) return;

        try (var stream = Files.walk(directory)) {
            stream.sorted((a, b) -> -a.compareTo(b)).forEach(SystemUtil::deletePathQuietly);
        }
    }

    private static void deletePathQuietly(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            LOGGER.warn("Failed to delete: {}", path, e);
        }
    }

    public static void setExecutablePermissions(Path file) throws IOException {
        var permissions = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
        );
        Files.setPosixFilePermissions(file, permissions);
    }

    public static void extractZip(Path zipFile, Path dir) throws IOException {
        try (var zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry = zis.getNextEntry();
            while (entry != null) {
                var targetPath = dir.resolve(entry.getName());

                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(zis, targetPath);
                }
                zis.closeEntry();
                entry = zis.getNextEntry();
            }
        }
    }

    public static void extractTarGz(Path tarGzFile, Path dir) throws IOException, InterruptedException {
        // Use system tar command for simplicity and reliability
        var pb = new ProcessBuilder("tar", "-xzf", tarGzFile.toString(), "-C", dir.toString());
        pb.redirectErrorStream(true);
        var process = pb.start();

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String error = readProcessError(process);
            throw new IOException("Failed to extract tar.gz: " + error);
        }
    }

    private static String readProcessError(Process process) throws IOException {
        try (InputStream errorStream = process.getInputStream()) {
            return new String(errorStream.readAllBytes());
        }
    }

    public static File download(String url, String path) throws IOException, URISyntaxException {
        var tmp = new File(path);
        if (url.startsWith("/") || url.matches("[A-Za-z]:.*")) {
            var sourceFile = new File(url);
            if (!sourceFile.exists()) {
                throw new FileNotFoundException("Local file not found: " + url);
            }
            Files.copy(sourceFile.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        }
        var uri = new URI(url).toURL();
        if ("file".equals(uri.getProtocol())) {
            Files.copy(new File(uri.getFile()).toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        }
        if (isFileAlreadyDownloaded(tmp, uri)) {
            LOGGER.info("File already exist, skipping download.");
            return tmp;
        }
        return downloadHttp(url, tmp);
    }

    public static File downloadHttp(String url, File tempFile) throws IOException, URISyntaxException {
        var uri = new URI(url).toURL();
        var connection = (HttpURLConnection) uri.openConnection();
        connection.setRequestMethod("GET");

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("Failed to download file. Server responded with code: " + responseCode);
        }

        try (var in = connection.getInputStream();
             var out = Files.newOutputStream(tempFile.toPath())) {
            var buffer = new byte[4096];
            int bytesRead = in.read(buffer);
            while (bytesRead != -1) {
                out.write(buffer, 0, bytesRead);
                bytesRead = in.read(buffer);
            }
        }
        return tempFile;
    }

    private static boolean isFileAlreadyDownloaded(File localFile, URL remoteUrl) throws IOException {
        if (localFile.exists()) {
            var connection = (HttpURLConnection) remoteUrl.openConnection();
            connection.setRequestMethod("HEAD");
            var responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                var localFileSize = localFile.length();
                var remoteFileSize = connection.getContentLengthLong();
                return localFileSize == remoteFileSize;
            }
        }
        return false;
    }
}
