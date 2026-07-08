package ai.core.server.memory.experiment;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Paginated response for experiment runs list.
 *
 * @author stephen
 */
public class ListExperimentRunsResponse {
    @Property(name = "runs")
    public List<ExperimentRunView> runs;

    @Property(name = "total")
    public Long total;
}
