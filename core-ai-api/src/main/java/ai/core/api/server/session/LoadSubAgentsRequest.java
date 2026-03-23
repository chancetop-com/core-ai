package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class LoadSubAgentsRequest {
    @NotNull
    @Property(name = "agent_ids")
    public List<String> agentIds = List.of();
}
