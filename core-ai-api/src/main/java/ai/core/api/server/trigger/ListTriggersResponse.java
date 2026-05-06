package ai.core.api.server.trigger;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class ListTriggersResponse {
    @NotNull
    @Property(name = "triggers")
    public List<TriggerView> triggers;

    @NotNull
    @Property(name = "total")
    public Long total;
}
