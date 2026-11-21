package ai.core.api.server.evaluation.request;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ImportDatasetRequest {
    @Property(name = "file_id")
    public String fileId;

    @Property(name = "name")
    public String name;
}
