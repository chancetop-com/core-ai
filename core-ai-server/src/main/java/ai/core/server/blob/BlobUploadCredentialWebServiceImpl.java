package ai.core.server.blob;

import ai.core.api.server.blob.BlobUploadCredentialRequest;
import ai.core.api.server.blob.BlobUploadCredentialView;
import ai.core.api.server.blob.BlobUploadCredentialWebService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;

import java.util.Map;
import java.util.UUID;

/**
 * Issues upload credentials (pre-signed URL + container/blob info) for direct browser-to-storage uploads.
 * <p>
 * The storage provider (Azure, MinIO, etc.) is abstracted behind {@link ObjectStorageService}.
 *
 * @author stephen
 */
public class BlobUploadCredentialWebServiceImpl implements BlobUploadCredentialWebService {
    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            // images
            Map.entry("image/jpeg", ".jpg"),
            Map.entry("image/png", ".png"),
            Map.entry("image/gif", ".gif"),
            Map.entry("image/webp", ".webp"),
            Map.entry("image/svg+xml", ".svg"),
            // videos
            Map.entry("video/mp4", ".mp4"),
            Map.entry("video/webm", ".webm"),
            Map.entry("video/quicktime", ".mov"),
            Map.entry("video/x-msvideo", ".avi"),
            // documents
            Map.entry("application/pdf", ".pdf"),
            Map.entry("application/msword", ".doc"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
            Map.entry("application/vnd.ms-excel", ".xls"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx"),
            // text / code / data
            Map.entry("text/plain", ".txt"),
            Map.entry("text/markdown", ".md"),
            Map.entry("text/csv", ".csv"),
            Map.entry("text/html", ".html"),
            Map.entry("text/css", ".css"),
            Map.entry("text/javascript", ".js"),
            Map.entry("application/javascript", ".js"),
            Map.entry("text/xml", ".xml"),
            Map.entry("application/xml", ".xml"),
            Map.entry("application/json", ".json"),
            Map.entry("application/x-yaml", ".yaml"),
            Map.entry("text/yaml", ".yaml"),
            // archives
            Map.entry("application/zip", ".zip"),
            Map.entry("application/x-tar", ".tar"),
            Map.entry("application/gzip", ".gz"),
            Map.entry("application/x-gzip", ".gz"));

    @Inject
    ObjectStorageServiceResolver resolver;

    @Override
    @PermissionsRequired(PermissionCodes.DASHBOARD_VIEW)
    public BlobUploadCredentialView get(BlobUploadCredentialRequest request) {
        var storageService = resolver.resolve();
        if (storageService == null) {
            throw new BadRequestException("Object storage is not configured", "OBJECT_STORAGE_NOT_CONFIGURED");
        }

        var contentType = request.contentType;
        if (contentType == null) contentType = "application/octet-stream";
        var category = request.category;
        var container = "sandbox".equals(category) ? resolver.sandboxContainer() : resolver.multimodalContainer();
        var ext = inferExtension(contentType);
        var prefix = "sandbox".equals(category) ? "uploads" : "ai/uploads";
        var blobName = prefix + "/" + UUID.randomUUID() + ext;

        var result = storageService.generateUploadCredential(container, blobName);

        var view = new BlobUploadCredentialView();
        view.uploadUrl = result.uploadUrl();
        view.blobUrl = result.blobUrl();
        view.container = result.container();
        view.blobName = result.blobName();
        view.expiresAt = result.expiresAt();
        return view;
    }

    private String inferExtension(String contentType) {
        return EXTENSIONS.getOrDefault(contentType, "");
    }
}
