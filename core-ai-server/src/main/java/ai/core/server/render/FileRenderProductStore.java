package ai.core.server.render;

import ai.core.server.file.FileService;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;

/**
 * FileService-backed product store: content-hash dedup upload — content-addressed storage is what
 * makes render products cacheable and re-addressable by hash.
 *
 * @author stephen
 */
public class FileRenderProductStore implements RenderProductStore {
    private static final java.util.regex.Pattern FILE_BY_ID = java.util.regex.Pattern.compile("^/api/files/([^/?#]+)/content$");
    private static final java.util.regex.Pattern FILE_BY_API_TOKEN = java.util.regex.Pattern.compile("^/api/public/artifacts/([^/?#]+)/content$");
    private static final java.util.regex.Pattern FILE_BY_SPA_TOKEN = java.util.regex.Pattern.compile("^/shared/artifacts/([^/?#]+)(?:/content)?$");

    private final HTTPClient httpClient = HTTPClient.builder()
        .connectTimeout(Duration.ofSeconds(10))
        .timeout(Duration.ofMinutes(5))
        .trustAll()
        .build();

    @Inject
    FileService fileService;

    @Override
    public StoredProduct storeBytes(String userId, String fileName, String contentType, byte[] bytes) {
        try {
            var tempFile = Files.createTempFile("render-product", suffix(fileName));
            Files.write(tempFile, bytes);
            var record = fileService.uploadIfAbsent(userId, fileName, contentType, tempFile);
            return new StoredProduct(record.id, record.contentHash, fileService.downloadUrl(record));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist render product: " + fileName, e);
        }
    }

    @Override
    public StoredProduct storeFromUrl(String userId, String fileName, String url) {
        var response = httpClient.execute(new HTTPRequest(HTTPMethod.GET, url));
        if (response.statusCode < 200 || response.statusCode >= 300)
            throw new BadRequestException("failed to download render product: HTTP " + response.statusCode + " from " + url);
        return storeBytes(userId, fileName, contentType(response.headers.get("Content-Type"), fileName), response.body);
    }

    /**
     * Lenient resolution: agents hand us whatever they have — a FileRecord id, a
     * "/api/files/{id}/content" URL, or an artifact share URL ("/api/public/artifacts/{token}/content",
     * "/shared/artifacts/{token}", absolute or path-only). Unresolvable refs return null instead of
     * throwing so one bad reference never breaks a whole board/render batch.
     */
    @Override
    public StoredProduct resolve(String fileId) {
        var record = findRecord(fileId);
        if (record == null) return null;
        return new StoredProduct(record.id, record.contentHash, fileService.downloadUrl(record));
    }

    private ai.core.server.domain.FileRecord findRecord(String ref) {
        if (ref == null || ref.isBlank()) return null;
        var path = ref.trim();
        var protocol = path.indexOf("://");
        if (protocol > 0) {
            var slash = path.indexOf('/', protocol + 3);
            path = slash > 0 ? path.substring(slash) : "";
        }
        try {
            var fileById = FILE_BY_ID.matcher(path);
            if (fileById.matches()) return fileService.get(fileById.group(1));
            var byApiToken = FILE_BY_API_TOKEN.matcher(path);
            if (byApiToken.matches()) return fileService.getShared(byApiToken.group(1));
            var bySpaToken = FILE_BY_SPA_TOKEN.matcher(path);
            if (bySpaToken.matches()) return fileService.getShared(bySpaToken.group(1));
            return fileService.get(ref.trim());
        } catch (NotFoundException e) {
            return null;
        }
    }

    @Override
    public StoredProduct resolveByHash(String userId, String contentHash) {
        var record = fileService.findByContentHash(userId, contentHash).orElse(null);
        if (record == null) return null;
        return new StoredProduct(record.id, record.contentHash, fileService.downloadUrl(record));
    }

    /** Accepts raw base64 or a data URL ("data:image/png;base64,..."). */
    public StoredProduct storeBase64(String userId, String fileName, String contentType, String base64) {
        var payload = base64;
        var resolvedType = contentType;
        if (payload.startsWith("data:")) {
            var comma = payload.indexOf(',');
            var header = payload.substring(5, comma);
            var semicolon = header.indexOf(';');
            if (semicolon > 0) resolvedType = header.substring(0, semicolon);
            payload = payload.substring(comma + 1);
        }
        return storeBytes(userId, fileName, resolvedType, Base64.getDecoder().decode(payload));
    }

    private String contentType(String headerValue, String fileName) {
        if (headerValue != null && !headerValue.isBlank()) return headerValue;
        return fileName.endsWith(".mp4") ? "video/mp4" : "image/png";
    }

    private String suffix(String fileName) {
        var dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot) : ".bin";
    }
}
