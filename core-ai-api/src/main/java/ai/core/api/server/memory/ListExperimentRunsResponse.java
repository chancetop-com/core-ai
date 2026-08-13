package ai.core.api.server.memory;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListExperimentRunsResponse {
    @Property(name = "runs")
    public List<ExperimentRunView> runs;

    @Property(name = "total")
    public Long total;
}
