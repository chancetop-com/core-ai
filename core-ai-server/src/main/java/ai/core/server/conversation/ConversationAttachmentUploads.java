package ai.core.server.conversation;

import ai.core.api.server.blob.BlobUploadCredentialView;
import ai.core.server.blob.BlobContentTypes;
import ai.core.server.blob.ObjectStorageServiceResolver;
import core.framework.web.exception.BadRequestException;

import java.util.UUID;

/**
 * Issues the pre-signed upload credential a direct-conversation client uses for one attachment. The
 * blob name is bound to the session, so a send can tell this conversation's uploads from any other.
 *
 * @author core-ai
 */
public final class ConversationAttachmentUploads {
    private static final String MULTIMODAL = "multimodal";
    private static final String SANDBOX = "sandbox";

    public static BlobUploadCredentialView credential(ObjectStorageServiceResolver resolver, String sessionId,
                                                      String contentType, String category) {
        var storageService = resolver.resolve();
        if (storageService == null) {
            throw new BadRequestException("Object storage is not configured", "OBJECT_STORAGE_NOT_CONFIGURED");
        }
        var mediaType = contentType != null && !contentType.isBlank() ? contentType : "application/octet-stream";
        var target = SANDBOX.equals(category) ? SANDBOX : MULTIMODAL;
        var container = ConversationAttachments.container(target, resolver.multimodalContainer(), resolver.sandboxContainer());
        var blobName = ConversationAttachments.blobPrefix(target, sessionId) + UUID.randomUUID() + BlobContentTypes.extensionOf(mediaType);
        var result = storageService.generateUploadCredential(container, blobName);

        var view = new BlobUploadCredentialView();
        view.uploadUrl = result.uploadUrl();
        view.blobUrl = result.blobUrl();
        view.container = result.container();
        view.blobName = result.blobName();
        view.expiresAt = result.expiresAt();
        return view;
    }

    private ConversationAttachmentUploads() {
    }
}
