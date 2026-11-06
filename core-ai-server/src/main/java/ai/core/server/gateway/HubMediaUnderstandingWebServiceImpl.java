package ai.core.server.gateway;

import ai.core.api.server.hubmedia.HubMediaUnderstandVideoRequest;
import ai.core.api.server.hubmedia.HubMediaUnderstandVideoResponse;
import ai.core.api.server.hubmedia.HubMediaUnderstandingWebService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.domain.SessionAttachmentRef;
import ai.core.server.domain.SessionAttachmentRefRepository;
import ai.core.server.rbac.PermissionsBypass;
import ai.core.server.web.auth.AuthContext;
import ai.core.tool.tools.UnderstandVideoTool;
import core.framework.inject.Inject;
import core.framework.util.Strings;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * The user-level counterpart of the sandbox's {@code understand_video} tool. A blob uploaded by the caller is
 * registered as a video attachment so the same {@link UnderstandVideoTool.VideoUnderstandingService} that runs
 * in a session resolves and understands it, with the caller as the owner.
 *
 * @author stephen
 */
@PermissionsBypass
public class HubMediaUnderstandingWebServiceImpl implements HubMediaUnderstandingWebService {
    private static final String UPLOAD_PREFIX = "ai/uploads/";
    private static final String LOCAL_SESSION_PREFIX = "local-";
    private static final int MAX_BLOB_NAME_LENGTH = 512;

    @Inject
    WebContext webContext;

    @Inject
    UnderstandVideoTool.VideoUnderstandingService videoUnderstandingService;

    @Inject
    SessionAttachmentRefRepository attachmentRepository;

    @Inject
    ObjectStorageServiceResolver objectStorageResolver;

    @Override
    public HubMediaUnderstandVideoResponse understandVideo(HubMediaUnderstandVideoRequest request) {
        var userId = AuthContext.userId(webContext);
        var sessionId = sessionId(request.sessionId, userId);
        if (Strings.isBlank(request.question)) throw new BadRequestException("question is required");
        var reference = resolveReference(userId, sessionId, request);
        var result = videoUnderstandingService.understand(
                new UnderstandVideoTool.AttachmentOwner(userId, sessionId), reference.id, request.model, request.question);
        var response = new HubMediaUnderstandVideoResponse();
        response.answer = result.answer();
        response.model = result.model();
        response.fileCache = result.fileCache();
        response.promptTokens = result.promptTokens();
        response.completionTokens = result.completionTokens();
        response.totalTokens = result.totalTokens();
        response.attachmentReferenceId = reference.id;
        response.sessionId = sessionId;
        return response;
    }

    private String sessionId(String requested, String userId) {
        if (!Strings.isBlank(requested)) return requested.trim();
        return LOCAL_SESSION_PREFIX + (userId == null ? "unknown" : userId);
    }

    private SessionAttachmentRef resolveReference(String userId, String sessionId, HubMediaUnderstandVideoRequest request) {
        if (!Strings.isBlank(request.attachmentReferenceId)) {
            var reference = new SessionAttachmentRef();
            reference.id = request.attachmentReferenceId;
            return reference;
        }
        if (Strings.isBlank(request.container) || Strings.isBlank(request.blobName)) {
            throw new BadRequestException("attachment_reference_id or container/blob_name is required");
        }
        var existing = attachmentRepository.findBySource(sessionId, userId, request.container, request.blobName);
        if (existing != null) return existing;
        return mintReference(userId, sessionId, request);
    }

    private SessionAttachmentRef mintReference(String userId, String sessionId, HubMediaUnderstandVideoRequest request) {
        var storage = objectStorageResolver.resolve();
        if (storage == null) throw new BadRequestException("object storage is not configured");
        var expectedContainer = objectStorageResolver.multimodalContainer();
        if (expectedContainer == null || !expectedContainer.equals(request.container)) {
            throw new BadRequestException("video must be uploaded to the multimodal container");
        }
        if (!request.blobName.startsWith(UPLOAD_PREFIX) || request.blobName.length() > MAX_BLOB_NAME_LENGTH
                || request.blobName.contains("..")) {
            throw new BadRequestException("invalid video blob name");
        }
        var metadata = storage.headObject(request.container, request.blobName);
        if (metadata == null || metadata.sizeBytes() == null || metadata.sizeBytes() <= 0) {
            throw new BadRequestException("video blob not found or empty");
        }
        var reference = new SessionAttachmentRef();
        reference.id = "video_" + UUID.randomUUID();
        reference.sessionId = sessionId;
        reference.userId = userId;
        reference.container = request.container;
        reference.blobName = request.blobName;
        reference.fileName = Strings.isBlank(request.fileName) ? "video.mp4" : request.fileName;
        reference.contentType = contentType(metadata.contentType(), request.contentType);
        reference.sourceETag = metadata.etag();
        reference.sourceSizeBytes = metadata.sizeBytes();
        reference.kind = SessionAttachmentRef.KIND_VIDEO;
        reference.createdAt = ZonedDateTime.now();
        attachmentRepository.insert(reference);
        return reference;
    }

    private String contentType(String storageContentType, String requestContentType) {
        if (storageContentType != null && storageContentType.startsWith("video/")) return storageContentType;
        if (requestContentType != null && requestContentType.startsWith("video/")) return requestContentType;
        return "video/mp4";
    }
}
