package ai.core.server.blob;

import ai.core.api.server.blob.BlobUploadCredentialView;
import core.framework.api.http.HTTPStatus;
import core.framework.web.Request;
import core.framework.web.Response;

import java.util.UUID;

/**
 * Issues upload credentials (SAS token + container/blob info) for direct browser-to-Azure-Blob uploads.
 *
 * @author stephen
 */
public class BlobUploadCredentialController {
    private static final int SAS_EXPIRY_MINUTES = 10;

    public AzureBlobSasService sasService;
    public String container;
    public String prefix;
    public String publicBaseUrl;

    public Response getCredential(Request request) {
        if (sasService == null) {
            return Response.text("Azure Blob Storage is not configured")
                    .status(HTTPStatus.INTERNAL_SERVER_ERROR);
        }

        var params = request.queryParams();
        var contentType = params.get("content_type");
        if (contentType == null) contentType = "application/octet-stream";
        var ext = inferExtension(contentType);
        var blobName = (prefix != null && !prefix.isBlank() ? prefix + "/" : "") + UUID.randomUUID() + ext;

        var result = sasService.generateContainerSas(container, blobName, SAS_EXPIRY_MINUTES);

        var view = new BlobUploadCredentialView();
        view.uploadUrl = result.uploadUrl();
        view.blobUrl = publicBaseUrl != null ? publicBaseUrl + "/" + result.container() + "/" + result.blobName() : result.blobUrl();
        view.container = result.container();
        view.blobName = result.blobName();
        view.expiresAt = result.expiresAt();

        return Response.bean(view);
    }

    private String inferExtension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            case "application/pdf" -> ".pdf";
            default -> "";
        };
    }
}
