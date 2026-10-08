package ai.core.api.server.session.sse;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class SseCustomEvent extends SseBaseEvent {
    @NotNull
    @NotBlank
    @Property(name = "name")
    public String name;

    @NotNull
    @Property(name = "data")
    public String data;

    @Property(name = "call_id")
    public String callId;
}
