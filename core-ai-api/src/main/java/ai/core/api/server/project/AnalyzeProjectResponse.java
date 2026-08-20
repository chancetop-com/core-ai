package ai.core.api.server.project;

import core.framework.api.json.Property;

/**
 * Result summary of a manual "Analyze now" run: how many targets were attributed to subjects,
 * how many attributed rows were consumed by the analysis, and how many subject updates were applied.
 *
 * @author stephen
 */
public class AnalyzeProjectResponse {
    @Property(name = "attributed")
    public Integer attributed;

    @Property(name = "analyzed")
    public Integer analyzed;

    @Property(name = "updated")
    public Integer updated;
}
