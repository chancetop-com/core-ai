package ai.core.server.blob;

import ai.core.api.server.blob.BlobUploadCredentialView;
import core.framework.api.http.HTTPStatus;
import core.framework.web.Request;
import core.framework.web.Response;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.UUID;

/**
 * Issues upload credentials (pre-signed URL + container/blob info) for direct browser-to-storage uploads.
 * <p>
 * The storage provider (Azure, MinIO, etc.) is abstracted behind {@link ObjectStorageService}.
 * To switch providers, change the binding in {@code ObjectStorageModule}.
 *
 * @author stephen
 */
public class BlobUploadCredentialController {

    public ObjectStorageService storageService;
    public String multimodalContainer = "uploads";
    public String sandboxContainer = "sandbox-uploads";

    public Response getCredential(Request request) {
        if (storageService == null) {
            return Response.text("Object storage is not configured")
                    .status(HTTPStatus.INTERNAL_SERVER_ERROR);
        }

        var params = request.queryParams();
        var contentType = params.get("content_type");
        if (contentType == null) contentType = "application/octet-stream";
        var category = params.get("category");
        var container = "sandbox".equals(category) ? sandboxContainer : multimodalContainer;
        var ext = inferExtension(contentType);
        var prefix = "sandbox".equals(category) ? "uploads" : "ai";
        var blobName = prefix + "/" + UUID.randomUUID() + ext;

        var result = storageService.generateUploadCredential(container, blobName);

        var view = new BlobUploadCredentialView();
        view.uploadUrl = result.uploadUrl();
        view.blobUrl = result.blobUrl();
        view.container = result.container();
        view.blobName = result.blobName();
        view.expiresAt = result.expiresAt();

        return Response.bean(view);
    }

    @SuppressFBWarnings("CC_CYCLOMATIC_COMPLEXITY")
    private String inferExtension(String contentType) {
        return switch (contentType) {
            // images
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            // documents
            case "application/pdf" -> ".pdf";
            case "application/msword" -> ".doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            case "application/vnd.ms-excel" -> ".xls";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx";
            // text / code / data
            case "text/plain", "text/markdown" -> ".txt";
            case "text/csv" -> ".csv";
            case "text/html" -> ".html";
            case "text/css" -> ".css";
            case "text/javascript", "application/javascript" -> ".js";
            case "text/xml", "application/xml" -> ".xml";
            case "application/json" -> ".json";
            case "application/x-yaml", "text/yaml" -> ".yaml";
            // archives
            case "application/zip" -> ".zip";
            case "application/x-tar" -> ".tar";
            case "application/gzip", "application/x-gzip" -> ".gz";
            default -> "";
        };
    }
}
