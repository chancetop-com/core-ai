package ai.core.server.share;

import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.SessionAttachmentRef;
import ai.core.server.domain.SessionAttachmentRefRepository;
import ai.core.server.domain.SessionShare;
import ai.core.server.domain.SessionShareFile;
import ai.core.server.file.FileService;
import ai.core.server.settings.SystemSettingsService;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures owned file references and rewrites their links into the same revocable session share.
 *
 * @author Xander
 */
public class SessionShareFileService {
    static final long MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024;
    private static final int MAX_FILES = 1000;
    private static final Pattern URL = Pattern.compile("(?i)(?:https?://|//)[^\\s<>\\\"`\\\\]+"
        + "|(?<![^\\s\\\"'(])/(?:api/files|api/public/artifacts|shared/artifacts)/[^\\s<>\\\"`\\\\]+");
    private static final Pattern PRIVATE_FILE = Pattern.compile("^/api/files/([^/?#]+)(?:/(?:content|thumbnail))?(?:[?#].*)?$");
    private static final Pattern PUBLIC_FILE = Pattern.compile("^/(?:api/public/artifacts|shared/artifacts)/([^/?#]+)(?:/content)?(?:[?#].*)?$");

    private static String path(String url) {
        try {
            var uri = URI.create(url);
            return uri.getPath() == null ? "" : uri.getPath();
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static String urlValue(String token) {
        int round = 0;
        int square = 0;
        for (int i = 0; i < token.length(); i++) {
            char value = token.charAt(i);
            if (value == '(') round++;
            else if (value == ')') round--;
            else if (value == '[') square++;
            else if (value == ']') square--;
        }
        int end = token.length();
        while (end > 0) {
            char value = token.charAt(end - 1);
            if (value == ')' && round < 0) round++;
            else if (value == ']' && square < 0) square++;
            else if (value != '\'') break;
            end--;
        }
        return token.substring(0, end);
    }

    private static boolean mentions(String text, String reference) {
        return reference != null && !reference.isEmpty()
            && Pattern.compile(Pattern.quote(reference) + "(?![A-Za-z0-9_./-])").matcher(text).find();
    }

    public static String downloadUrl(SessionShare share, String refId) {
        return "/api/public/session-shares/" + share.token + "/files/" + refId + "/content";
    }

    private static void checkSize(Long size) {
        if (size == null || size < 0 || size > MAX_DOWNLOAD_BYTES) {
            throw new BadRequestException("shared downloads require a known size of at most 64 MiB");
        }
    }

    @Inject
    FileService fileService;
    @Inject
    SessionAttachmentRefRepository attachmentRepository;
    @Inject
    ObjectStorageServiceResolver storageResolver;
    @Inject
    PublicUrlConfiguration publicUrl;
    @Inject
    SystemSettingsService settings;

    public List<SessionShareFile> capture(ChatSession session, List<ChatMessage> messages) {
        var files = new LinkedHashMap<String, SessionShareFile>();
        if (session.artifacts != null) {
            for (var artifact : session.artifacts) {
                var record = ownedFile(artifact.fileId, session.userId);
                if (record == null) continue;
                var file = fromFile(record);
                file.title = artifact.title;
                file.description = artifact.description;
                files.put("file:" + record.id, file);
            }
        }
        var references = new ArrayList<SessionAttachmentRef>(attachmentRepository.findSandboxAttachments(session.id, session.userId));
        references.addAll(attachmentRepository.findOwnedVideos(session.id, session.userId));
        for (var message : messages) {
            if (!session.id.equals(message.sessionId)) continue;
            var text = JsonUtil.toJson(message);
            for (var reference : references) {
                if (mentions(text, reference.id) || mentions(text, reference.targetPath)) {
                    addReference(files, reference, session, reference.targetPath);
                }
            }
            var matcher = URL.matcher(text);
            while (matcher.find()) captureUrl(files, urlValue(matcher.group()), session);
            if (files.size() > MAX_FILES) throw new BadRequestException("too many files to share");
        }
        return new ArrayList<>(files.values());
    }

    private void captureUrl(Map<String, SessionShareFile> files, String url, ChatSession session) {
        var path = localPath(url);
        var privateFile = PRIVATE_FILE.matcher(path);
        if (privateFile.matches()) {
            addFile(files, ownedFile(privateFile.group(1), session.userId));
            return;
        }
        var publicFile = PUBLIC_FILE.matcher(path);
        if (publicFile.matches()) {
            try {
                var record = fileService.getShared(publicFile.group(1));
                if (Objects.equals(record.userId, session.userId)) addFile(files, record);
            } catch (NotFoundException ignored) {
                return;
            }
            return;
        }
        var location = locate(url);
        if (location == null) return;
        var reference = attachmentRepository.findBySource(session.id, session.userId, location.container(), location.blobName());
        addReference(files, reference, session, url);
    }

    private void addFile(Map<String, SessionShareFile> files, FileRecord record) {
        if (record != null) files.putIfAbsent("file:" + record.id, fromFile(record));
    }

    private void addReference(Map<String, SessionShareFile> files, SessionAttachmentRef reference, ChatSession session, String sourceUrl) {
        if (reference == null || !Objects.equals(reference.sessionId, session.id)
            || !Objects.equals(reference.userId, session.userId)) return;
        var key = "attachment:" + reference.id;
        if (files.containsKey(key)) return;
        var file = new SessionShareFile();
        file.id = UUID.randomUUID().toString();
        file.attachmentRefId = reference.id;
        file.container = reference.container;
        file.blobName = reference.blobName;
        file.fileName = reference.fileName;
        file.contentType = reference.contentType;
        file.size = reference.sourceSizeBytes;
        file.sourceUrl = sourceUrl;
        files.put(key, file);
    }

    private FileRecord ownedFile(String id, String ownerId) {
        if (id == null) return null;
        try {
            return fileService.getOwned(id, ownerId);
        } catch (NotFoundException | ForbiddenException ignored) {
            return null;
        }
    }

    private SessionShareFile fromFile(FileRecord record) {
        var file = new SessionShareFile();
        file.id = UUID.randomUUID().toString();
        file.fileId = record.id;
        file.fileName = record.fileName;
        file.contentType = record.contentType;
        file.size = record.size;
        if (record.shareToken != null) file.sourceUrl = "/api/public/artifacts/" + record.shareToken + "/content";
        if (record.storagePath != null && record.storagePath.contains("/")) {
            int separator = record.storagePath.indexOf('/');
            file.container = record.storagePath.substring(0, separator);
            file.blobName = record.storagePath.substring(separator + 1);
        }
        return file;
    }

    public String rewrite(String text, SessionShare share) {
        if (text == null || text.isEmpty()) return text;
        var matcher = URL.matcher(text);
        var result = new StringBuilder();
        while (matcher.find()) {
            var raw = matcher.group();
            var url = urlValue(raw);
            var file = match(url, share.files);
            var replacement = file != null ? downloadUrl(share, file.id) : platformUrl(url) ? "[attachment unavailable]" : url;
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement + raw.substring(url.length())));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private SessionShareFile match(String url, List<SessionShareFile> files) {
        if (files == null) return null;
        var path = localPath(url);
        var privateFile = PRIVATE_FILE.matcher(path);
        var privateId = privateFile.matches() ? privateFile.group(1) : null;
        var publicFile = PUBLIC_FILE.matcher(path);
        var publicToken = publicFile.matches() ? publicFile.group(1) : null;
        var location = locate(url);
        for (var file : files) {
            if (privateId != null && privateId.equals(file.fileId)) return file;
            if (url.equals(file.sourceUrl) || path.equals(file.sourceUrl)) return file;
            if (publicToken != null && file.sourceUrl != null
                && file.sourceUrl.equals("/api/public/artifacts/" + publicToken + "/content")) return file;
            if (location != null && Objects.equals(file.container, location.container()) && Objects.equals(file.blobName, location.blobName())) return file;
        }
        return null;
    }

    private boolean platformUrl(String url) {
        var path = localPath(url);
        return path.startsWith("/api/files/") || path.startsWith("/api/public/artifacts/")
            || path.startsWith("/shared/artifacts/") || locate(url) != null;
    }

    private ObjectStorageServiceResolver.BlobLocation locate(String url) {
        var clean = url.split("[?#]", 2)[0];
        if (!trustedStorageUrl(clean)) return null;
        var location = storageResolver.locate(clean);
        if (location != null) return location;
        for (var base : new String[]{storageResolver.minioEndpoint, storageResolver.minioPublicBaseUrl,
            storageResolver.webAssetsPublicBaseUrl()}) {
            if (base == null || base.isBlank()) continue;
            var prefix = base.replaceAll("/+$", "") + "/";
            if (!clean.startsWith(prefix)) continue;
            var objectPath = clean.substring(prefix.length());
            int separator = objectPath.indexOf('/');
            if (separator > 0 && separator < objectPath.length() - 1) {
                return new ObjectStorageServiceResolver.BlobLocation(objectPath.substring(0, separator), objectPath.substring(separator + 1));
            }
        }
        return null;
    }

    private boolean trustedStorageUrl(String url) {
        for (var base : new String[]{storageResolver.minioEndpoint, storageResolver.minioPublicBaseUrl,
            storageResolver.webAssetsPublicBaseUrl(), settings == null ? null : settings.azureBlobCdnBaseUrl()}) {
            if (base == null || base.isBlank()) continue;
            var normalized = base.contains("://") ? base : "https://" + base;
            if (url.startsWith(normalized.replaceAll("/+$", "") + "/")) return true;
        }
        if (settings == null || settings.azureBlobAccountName() == null) return false;
        try {
            var uri = URI.create(url);
            return "https".equals(uri.getScheme()) && uri.getUserInfo() == null
                && (settings.azureBlobAccountName() + ".blob.core.windows.net").equalsIgnoreCase(uri.getHost());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String localPath(String url) {
        if (url.startsWith("/") && !url.startsWith("//")) return path(url);
        if (publicUrl == null) return "";
        try {
            var target = URI.create(url);
            var origin = URI.create(publicUrl.value());
            if (target.getUserInfo() != null || target.getHost() == null || origin.getHost() == null) return "";
            if (target.getScheme() == null || !target.getScheme().equalsIgnoreCase(origin.getScheme()) || !target.getHost().equalsIgnoreCase(origin.getHost())
                || target.getPort() != origin.getPort()) return "";
            return path(url);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    public SessionShareFile requireFile(SessionShare share, String refId) {
        if (share.files != null) {
            for (var file : share.files) {
                if (Objects.equals(file.id, refId)) return file;
            }
        }
        throw new NotFoundException("shared file unavailable");
    }

    public byte[] read(SessionShare share, SessionShareFile file) {
        checkSize(file.size);
        if (file.fileId != null) {
            var record = ownedFile(file.fileId, share.ownerUserId);
            if (record == null) throw new NotFoundException("shared file unavailable");
            checkSize(record.size);
            var bytes = fileService.getBytes(record);
            checkSize((long) bytes.length);
            return bytes;
        }
        var reference = attachmentRepository.findOwned(file.attachmentRefId, share.sessionId, share.ownerUserId);
        if (reference == null || !Objects.equals(file.container, reference.container) || !Objects.equals(file.blobName, reference.blobName)) {
            throw new NotFoundException("shared file unavailable");
        }
        var storage = storageResolver.resolve();
        if (storage == null) throw new NotFoundException("shared file unavailable");
        var metadata = storage.headObject(reference.container, reference.blobName);
        checkSize(metadata.sizeBytes());
        if (reference.sourceETag != null && !reference.sourceETag.equals(metadata.etag())) {
            throw new NotFoundException("shared file has changed");
        }
        var bytes = storage.downloadObject(reference.container, reference.blobName);
        checkSize((long) bytes.length);
        return bytes;
    }

}
