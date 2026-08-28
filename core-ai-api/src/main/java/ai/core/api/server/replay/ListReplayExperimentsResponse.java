package ai.core.api.server.replay;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListReplayExperimentsResponse {
    @Property(name = "experiments")
    public List<ReplayExperimentListItemView> experiments;

    @Property(name = "total")
    public Long total;
}
