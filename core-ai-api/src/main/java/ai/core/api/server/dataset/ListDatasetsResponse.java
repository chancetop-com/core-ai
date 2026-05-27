package ai.core.api.server.dataset;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListDatasetsResponse {
    @Property(name = "datasets")
    public List<DatasetView> datasets;

    @Property(name = "total")
    public Long total;
}
