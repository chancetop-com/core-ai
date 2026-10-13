package ai.core.api.server.comparison;

import ai.core.api.server.tool.ToolRefView;
import core.framework.api.json.Property;

import java.util.List;

/**
 * Final tool confirmation at start; both lists are optional and fall back to what the draft stored.
 *
 * @author stephen
 */
public class StartAgentComparisonRequest {
    @Property(name = "effective_tools_a")
    public List<ToolRefView> effectiveToolsA;

    @Property(name = "effective_tools_b")
    public List<ToolRefView> effectiveToolsB;
}
