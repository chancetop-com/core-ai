package ai.core.api.server.evaluation.response;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListDatasetsResponse {
    @Property(name = "datasets")
    public List<DatasetRevisionView> datasets;
}
