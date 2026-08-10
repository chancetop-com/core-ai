package ai.core.server.web;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Uploads the built frontend assets to the public object storage container at startup.
 * <p>
 * Asset file names carry content hashes, so an existing blob is never re-uploaded.
 * The redirect is only enabled after every missing file is uploaded and the public URL
 * probe succeeds; any failure keeps serving from the local image.
 *
 * @author stephen
 */
public class WebAssetsUploader {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebAssetsUploader.class);
    private static final String ASSETS_PREFIX = "web-assets/";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    @Inject
    ObjectStorageServiceResolver storageResolver;

    HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public void upload(Path webDir, StaticFileController controller) {
        try {
            var storage = storageResolver.resolve();
            var publicBaseUrl = storageResolver.webAssetsPublicBaseUrl();
            if (storage == null || publicBaseUrl == null || publicBaseUrl.isBlank()) {
                LOGGER.info("web assets redirect disabled, object storage or public base url not configured");
                return;
            }
            var container = storageResolver.multimodalContainer();
            var redirectBase = storageResolver.webAssetsRedirectBase();
            var files = listAssets(webDir);
            if (files.isEmpty()) {
                LOGGER.info("web assets redirect disabled, no assets directory found");
                return;
            }
            uploadMissing(storage, container, webDir, files);
            if (probePublicUrl(redirectBase + "/" + relativePath(webDir, files.get(0)))) {
                controller.enableWebAssetsRedirect(redirectBase);
                LOGGER.info("web assets redirect enabled, container={}, files={}", container, files.size());
            } else {
                LOGGER.error("web assets redirect disabled, public url probe failed, container={}", container);
            }
        } catch (RuntimeException e) {
            LOGGER.error("web assets upload failed, keeping local file serving", e);
        }
    }

    private void uploadMissing(ObjectStorageService storage, String container, Path webDir, List<Path> files) {
        int uploaded = 0;
        for (var file : files) {
            var blob = ASSETS_PREFIX + relativePath(webDir, file);
            if (storage.exists(container, blob)) continue;
            storage.uploadObject(container, blob, file, contentType(blob));
            uploaded++;
        }
        if (uploaded > 0) {
            LOGGER.info("web assets uploaded, container={}, uploaded={}", container, uploaded);
        }
    }

    private boolean probePublicUrl(String url) {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(PROBE_TIMEOUT)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException e) {
            LOGGER.warn("web assets public url probe failed, url={}, error={}", url, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("web assets public url probe failed, url={}, error={}", url, e.getMessage());
            return false;
        }
    }

    private List<Path> listAssets(Path webDir) {
        var assetsDir = webDir.resolve("assets");
        if (!Files.isDirectory(assetsDir)) return List.of();
        try (var stream = Files.walk(assetsDir)) {
            return stream.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list assets directory", e);
        }
    }

    private String relativePath(Path assetsDir, Path file) {
        return assetsDir.relativize(file).toString().replace('\\', '/');
    }

    private String contentType(String blob) {
        if (blob.endsWith(".js") || blob.endsWith(".mjs")) return "application/javascript";
        if (blob.endsWith(".css")) return "text/css";
        if (blob.endsWith(".svg")) return "image/svg+xml";
        if (blob.endsWith(".png")) return "image/png";
        if (blob.endsWith(".jpg") || blob.endsWith(".jpeg")) return "image/jpeg";
        if (blob.endsWith(".webp")) return "image/webp";
        if (blob.endsWith(".woff2")) return "font/woff2";
        if (blob.endsWith(".woff")) return "font/woff";
        if (blob.endsWith(".ttf")) return "font/ttf";
        if (blob.endsWith(".map")) return "application/json";
        return "application/octet-stream";
    }
}
