package ai.core.api.server.session;

import ai.core.api.server.tool.ToolRefView;
import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class LoadToolsRequest {
    @NotNull
    @Property(name = "tools")
    public List<ToolRefView> tools = List.of();
}
