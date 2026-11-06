package ai.core.api.server.hubmedia;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class HubMediaUnderstandVideoResponse {
    @Property(name = "answer")
    public String answer;

    @Property(name = "model")
    public String model;

    @Property(name = "file_cache")
    public String fileCache;

    @Property(name = "prompt_tokens")
    public Long promptTokens;

    @Property(name = "completion_tokens")
    public Long completionTokens;

    @Property(name = "total_tokens")
    public Long totalTokens;

    /** The reference minted for a blob call; pass it back as {@code attachment_reference_id} with the same session. */
    @Property(name = "attachment_reference_id")
    public String attachmentReferenceId;

    @Property(name = "session_id")
    public String sessionId;
}
