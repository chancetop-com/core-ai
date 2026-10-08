package ai.core.server.skill;

import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.domain.SkillResource;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Object storage for skill resource bytes. Blob names are content addressed and immutable
 * ({@code skills/{skillId}/resources/{sha256}}), so retries are idempotent, identical resources
 * deduplicate, and a document never points at bytes that a failed write replaced.
 *
 * @author stephen
 */
public class SkillBlobStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillBlobStore.class);
    private static final String RESOURCE_ROOT = "skills/";
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("css", "text/css"),
            Map.entry("js", "text/javascript"),
            Map.entry("mjs", "text/javascript"),
            Map.entry("json", "application/json"),
            Map.entry("txt", "text/plain"),
            Map.entry("md", "text/markdown"),
            Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"),
            Map.entry("xml", "application/xml"),
            Map.entry("yml", "application/yaml"),
            Map.entry("yaml", "application/yaml"),
            Map.entry("csv", "text/csv"),
            Map.entry("zip", "application/zip"),
            Map.entry("gz", "application/gzip"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("wasm", "application/wasm"));

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String contentTypeOf(String path) {
        if (path == null) return DEFAULT_CONTENT_TYPE;
        var slash = path.lastIndexOf('/');
        var dot = path.lastIndexOf('.');
        if (dot <= slash || dot == path.length() - 1) return DEFAULT_CONTENT_TYPE;
        return CONTENT_TYPES.getOrDefault(path.substring(dot + 1).toLowerCase(Locale.ROOT), DEFAULT_CONTENT_TYPE);
    }

    private static String containerOf(String storagePath) {
        return storagePath.substring(0, storagePath.indexOf('/'));
    }

    private static String blobOf(String storagePath) {
        return storagePath.substring(storagePath.indexOf('/') + 1);
    }

    @Inject
    ObjectStorageServiceResolver storageResolver;

    public boolean configured() {
        return storageResolver.resolve() != null;
    }

    /** Uploads the bytes and returns the {@code container/blob} path recorded on the resource. */
    public String put(String skillId, String path, byte[] bytes) {
        var storage = storageResolver.resolve();
        if (storage == null) throw new BadRequestException("object storage is not configured");
        var blobName = RESOURCE_ROOT + skillId + "/resources/" + sha256(bytes);
        var container = storageResolver.artifactContainer();
        storage.uploadObject(container, blobName, bytes, contentTypeOf(path));
        return container + "/" + blobName;
    }

    public byte[] fetch(String storagePath) {
        var storage = storageResolver.resolve();
        if (storage == null) throw new IllegalStateException("object storage is not configured, storagePath=" + storagePath);
        return storage.downloadObject(containerOf(storagePath), blobOf(storagePath));
    }

    /** Deletes the blobs of the previous resource set that the current one no longer references. */
    public void deleteReplaced(List<SkillResource> previous, List<SkillResource> current) {
        if (previous == null || previous.isEmpty()) return;
        Set<String> kept = new HashSet<>();
        if (current != null) {
            for (var resource : current) {
                if (resource.storagePath != null) kept.add(resource.storagePath);
            }
        }
        for (var resource : previous) {
            if (resource.storagePath != null && !kept.contains(resource.storagePath)) deleteQuietly(resource.storagePath);
        }
    }

    private void deleteQuietly(String storagePath) {
        try {
            var storage = storageResolver.resolve();
            if (storage == null) return;
            storage.deleteObject(containerOf(storagePath), blobOf(storagePath));
        } catch (RuntimeException e) {
            LOGGER.warn("failed to delete skill resource blob, storagePath={}", storagePath, e);
        }
    }
}
