package ai.core.api.server.comparison;

import ai.core.api.server.tool.ToolRefView;
import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ComparisonVariantView {
    @Property(name = "variant")
    public String variant;

    @Property(name = "label")
    public String label;

    @Property(name = "model")
    public String model;

    /** human-readable diff against the other variant (manager view only) */
    @Property(name = "summary")
    public String summary;

    @Property(name = "effective_tools")
    public List<ToolRefView> effectiveTools;

    /** full frozen config; null in the blind participant view */
    @Property(name = "config")
    public ComparisonVariantConfigView config;
}
