package ai.core.api.server.comparison;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class UpdateAgentVersionRequest {
    @Property(name = "label")
    public String label;

    @Property(name = "config")
    public ComparisonVariantConfigView config;
}
