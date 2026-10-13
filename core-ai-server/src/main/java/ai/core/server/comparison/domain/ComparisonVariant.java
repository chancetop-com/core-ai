package ai.core.server.comparison.domain;

import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.ToolRef;
import core.framework.mongo.Field;

import java.util.List;

/**
 * One frozen variant of a comparison: the full executable config snapshot plus the effective-tool
 * whitelist confirmed by the owner at start (the only tool refs a comparison session may assemble).
 *
 * @author stephen
 */
public class ComparisonVariant {
    @Field(name = "label")
    public String label;

    @Field(name = "config")
    public AgentPublishedConfig config;

    @Field(name = "effective_tools")
    public List<ToolRef> effectiveTools;
}
