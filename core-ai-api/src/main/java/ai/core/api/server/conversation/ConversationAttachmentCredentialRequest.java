package ai.core.api.server.conversation;

import core.framework.api.web.service.QueryParam;

/**
 * Upload credential for one direct-conversation attachment. The server decides the container and the
 * session-scoped blob name; the client may only choose the content type and whether the file is
 * consumed as multimodal context or staged into the session sandbox.
 *
 * @author core-ai
 */
public class ConversationAttachmentCredentialRequest {
    @QueryParam(name = "content_type")
    public String contentType;

    /** {@code multimodal} (image/video/pdf) or {@code sandbox} (any other file). */
    @QueryParam(name = "category")
    public String category;
}
