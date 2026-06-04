package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Field;

/**
 * @author stephen
 */
public class AgentDatasetPermission {
    @NotNull
    @Field(name = "dataset_id")
    public String datasetId;

    @NotNull
    @Field(name = "permission")
    public DatasetPermission permission = DatasetPermission.READ;
}
