package ai.core.api.server.memory;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListExperimentConfigsResponse {
    @Property(name = "configs")
    public List<ExperimentConfigListItemView> configs;

    @Property(name = "total")
    public Long total;
}
