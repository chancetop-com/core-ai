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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * @author stephen
 */
public class SystemUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(SystemUtil.class);
    private static final int HTTP_CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int HTTP_READ_TIMEOUT_MILLIS = 30_000;

    public static Platform detectPlatform() {
        var os = System.getProperty("os.name").toLowerCase(Locale.getDefault());
        return Platform.fromName(os);
    }

    public static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) return;

        try (var stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(SystemUtil::deletePathQuietly);
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
                    var parent = targetPath.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
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
            return new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static File download(String url, String path) throws IOException, URISyntaxException {
        var tmp = new File(path);
        if (url.startsWith("/") || url.matches("[A-Za-z]:.*")) {
            var sourceFile = new File(url);
            if (!sourceFile.exists()) {
                throw new FileNotFoundException("Local file not found: " + url);
            }
            Files.copy(Path.of(url), Path.of(path), StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        }
        var uri = new URI(url).toURL();
        if ("file".equals(uri.getProtocol())) {
            Files.copy(Path.of(uri.getFile()), Path.of(path), StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        }
        return downloadHttpWithResume(url, tmp, uri);
    }

    private static File downloadHttpWithResume(String url, File tempFile, URL uri) throws IOException, URISyntaxException {
        long existingSize = tempFile.exists() ? tempFile.length() : 0;

        if (existingSize <= 0) {
            return downloadHttp(url, tempFile, 0);
        }

        var headConn = (HttpURLConnection) uri.openConnection();
        configureTimeouts(headConn, HTTP_CONNECT_TIMEOUT_MILLIS, HTTP_READ_TIMEOUT_MILLIS);
        int headCode;
        long remoteSize;
        try {
            headConn.setRequestMethod("HEAD");
            headCode = headConn.getResponseCode();
            remoteSize = headConn.getContentLengthLong();
        } finally {
            headConn.disconnect();
        }
        if (headCode != HttpURLConnection.HTTP_OK) {
            return downloadHttp(url, tempFile, 0);
        }

        if (remoteSize > 0 && existingSize == remoteSize) {
            LOGGER.debug("File already downloaded, skipping.");
            return tempFile;
        }
        if (remoteSize > 0 && existingSize < remoteSize) {
            LOGGER.debug("Resuming download from byte {} (total {})", existingSize, remoteSize);
            return downloadHttp(url, tempFile, existingSize);
        }
        // existing file is larger than remote or remote size unknown, re-download
        Files.delete(tempFile.toPath());
        return downloadHttp(url, tempFile, 0);
    }

    public static File downloadHttp(String url, File tempFile) throws IOException, URISyntaxException {
        return downloadHttp(url, tempFile, 0);
    }

    private static File downloadHttp(String url, File tempFile, long resumeFrom) throws IOException, URISyntaxException {
        return downloadHttp(url, tempFile, resumeFrom, HTTP_CONNECT_TIMEOUT_MILLIS, HTTP_READ_TIMEOUT_MILLIS);
    }

    static File downloadHttp(String url, File tempFile, long resumeFrom, int connectTimeoutMillis, int readTimeoutMillis) throws IOException, URISyntaxException {
        var uri = new URI(url).toURL();
        var connection = (HttpURLConnection) uri.openConnection();
        configureTimeouts(connection, connectTimeoutMillis, readTimeoutMillis);
        try {
            connection.setRequestMethod("GET");
            if (resumeFrom > 0) {
                connection.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
            }

            int responseCode = connection.getResponseCode();
            boolean isResume = resumeFrom > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL;
            if (responseCode != HttpURLConnection.HTTP_OK && !isResume) {
                throw new IOException("Failed to download file. Server responded with code: " + responseCode);
            }

            copyResponseBody(connection, tempFile, resumeFrom);
            return tempFile;
        } finally {
            connection.disconnect();
        }
    }

    private static void copyResponseBody(HttpURLConnection connection, File tempFile, long resumeFrom) throws IOException {
        try (var in = connection.getInputStream();
             var out = Files.newOutputStream(tempFile.toPath(), resumeFrom > 0 ? StandardOpenOption.APPEND : StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            var buffer = new byte[8192];
            int bytesRead = in.read(buffer);
            while (bytesRead != -1) {
                out.write(buffer, 0, bytesRead);
                bytesRead = in.read(buffer);
            }
        }
    }

    private static void configureTimeouts(HttpURLConnection connection, int connectTimeoutMillis, int readTimeoutMillis) {
        connection.setConnectTimeout(connectTimeoutMillis);
        connection.setReadTimeout(readTimeoutMillis);
    }
}
