package ai.core.api.server.evaluation.response;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListTrialsResponse {
    @Property(name = "trials")
    public List<TrialView> trials;

    @Property(name = "total")
    public Long total;
}
