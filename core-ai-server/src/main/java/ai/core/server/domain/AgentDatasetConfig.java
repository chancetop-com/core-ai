package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Field;

/**
 * @author stephen
 */
public class AgentDatasetConfig {
    @NotNull
    @Field(name = "dataset_id")
    public String datasetId;

    @NotNull
    @Field(name = "permission")
    public DatasetPermission permission = DatasetPermission.READ;

    @Field(name = "is_output")
    public Boolean isOutput;
}
