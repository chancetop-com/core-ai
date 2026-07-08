package ai.core.server.memory.experiment;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Paginated response for experiment configs list.
 *
 * @author stephen
 */
public class ListExperimentConfigsResponse {
    @Property(name = "configs")
    public List<ExperimentConfigListItemView> configs;

    @Property(name = "total")
    public Long total;
}
