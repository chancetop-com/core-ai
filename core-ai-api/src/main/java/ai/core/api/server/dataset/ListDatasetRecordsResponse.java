package ai.core.api.server.dataset;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListDatasetRecordsResponse {
    @Property(name = "records")
    public List<DatasetRecordView> records;

    @Property(name = "total")
    public Long total;
}
