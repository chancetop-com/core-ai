package ai.core.api.server.comparison;

import ai.core.api.server.tool.ToolRefView;
import core.framework.api.json.Property;

import java.util.List;

/**
 * Draft edit of a comparison: variant B config patch (null field = keep), participants, budget and
 * the effective-tool confirmation lists. Tool confirmation may also be sent on start.
 *
 * @author stephen
 */
public class UpdateAgentComparisonRequest {
    @Property(name = "variant_b")
    public ComparisonVariantConfigView variantB;

    @Property(name = "participant_user_ids")
    public List<String> participantUserIds;

    @Property(name = "budget_usd")
    public Double budgetUsd;

    @Property(name = "effective_tools_a")
    public List<ToolRefView> effectiveToolsA;

    @Property(name = "effective_tools_b")
    public List<ToolRefView> effectiveToolsB;
}
