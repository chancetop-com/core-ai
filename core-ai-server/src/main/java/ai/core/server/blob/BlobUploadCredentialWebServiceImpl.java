package ai.core.server.blob;

import ai.core.api.server.blob.BlobUploadCredentialRequest;
import ai.core.api.server.blob.BlobUploadCredentialView;
import ai.core.api.server.blob.BlobUploadCredentialWebService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;

import java.util.UUID;

/**
 * Issues upload credentials (pre-signed URL + container/blob info) for direct browser-to-storage uploads.
 * <p>
 * The storage provider (Azure, MinIO, etc.) is abstracted behind {@link ObjectStorageService}.
 *
 * @author stephen
 */
public class BlobUploadCredentialWebServiceImpl implements BlobUploadCredentialWebService {
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
        var ext = BlobContentTypes.extensionOf(contentType);
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
}
