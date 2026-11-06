package ai.core.api.server.hubmedia;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * A user-scoped video-understanding call for clients outside an agent session (the local CLI, the desktop app).
 * Either {@code attachment_reference_id} or the blob coordinates must be set.
 *
 * @author stephen
 */
public class HubMediaUnderstandVideoRequest {
    @Property(name = "attachment_reference_id")
    public String attachmentReferenceId;

    @Property(name = "session_id")
    public String sessionId;

    @Property(name = "container")
    public String container;

    @Property(name = "blob_name")
    public String blobName;

    @Property(name = "content_type")
    public String contentType;

    @Property(name = "file_name")
    public String fileName;

    @NotNull
    @Property(name = "question")
    public String question;

    @Property(name = "model")
    public String model;
}
