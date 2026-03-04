package ai.core.api.server.session.sse;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class SseTextChunkEvent extends SseBaseEvent {
    @NotNull
    @NotBlank
    @Property(name = "content")
    public String content;

    @NotNull
    @Property(name = "is_final_chunk")
    public Boolean isFinalChunk;
}
