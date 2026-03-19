package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class LoadToolsRequest {
    @NotNull
    @Property(name = "tool_ids")
    public List<String> toolIds = List.of();
}
