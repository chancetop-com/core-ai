package ai.core.server.file;

import ai.core.server.domain.FileRecord;
import ai.core.tool.tools.InternalUrlResolver;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * @author stephen
 */
public class FileDownloadUrlResolver implements InternalUrlResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileDownloadUrlResolver.class);

    // /api/files/{id}/content
    private static final Pattern FILE_BY_ID = Pattern.compile("^/api/files/([^/?#]+)/content$");
    // /api/public/artifacts/{token}/content
    private static final Pattern FILE_BY_API_TOKEN = Pattern.compile("^/api/public/artifacts/([^/?#]+)/content$");
    // /shared/artifacts/{token} [/content] — SPA shared route
    private static final Pattern FILE_BY_SPA_TOKEN = Pattern.compile("^/shared/artifacts/([^/?#]+)(?:/content)?$");

    private final FileService fileService;
    private final String publicUrlPrefix;

    public FileDownloadUrlResolver(FileService fileService, String publicUrlPrefix) {
        this.fileService = fileService;
        this.publicUrlPrefix = publicUrlPrefix;
    }

    @Override
    public InternalUrlResult resolve(String url, String method) {
        if (!"GET".equals(method)) return null;
        if (publicUrlPrefix == null || publicUrlPrefix.isEmpty()) return null;
        if (!url.startsWith(publicUrlPrefix)) return null;

        var path = url.substring(publicUrlPrefix.length());
        // strip trailing slash from prefix if present so path always starts with /
        if (!path.startsWith("/")) path = "/" + path;

        return resolvePath(path);
    }

    private InternalUrlResult resolvePath(String path) {
        try {
            var record = findRecord(path);
            if (record == null) return null;

            var data = fileService.getBytes(record);
            LOGGER.debug("internal file resolve, path={}, id={}, size={}", path, record.id, record.size);
            return new InternalUrlResult(200, record.contentType, data);
        } catch (NotFoundException e) {
            LOGGER.debug("internal file resolve not found, path={}", path);
            return new InternalUrlResult(404, "text/plain", e.getMessage().getBytes());
        }
    }

    private FileRecord findRecord(String path) {
        var matcher = FILE_BY_ID.matcher(path);
        if (matcher.matches()) {
            return fileService.get(matcher.group(1));
        }
        matcher = FILE_BY_API_TOKEN.matcher(path);
        if (matcher.matches()) {
            return fileService.getShared(matcher.group(1));
        }
        matcher = FILE_BY_SPA_TOKEN.matcher(path);
        if (matcher.matches()) {
            return fileService.getShared(matcher.group(1));
        }
        return null;
    }
}
