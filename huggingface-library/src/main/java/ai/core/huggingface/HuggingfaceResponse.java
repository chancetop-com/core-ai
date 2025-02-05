package ai.core.huggingface;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class HuggingfaceResponse {
    @NotNull
    @Property(name = "event_id")
    public String eventId;
}
