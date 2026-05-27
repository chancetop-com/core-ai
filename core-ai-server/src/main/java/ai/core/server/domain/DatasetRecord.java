package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
@Collection(name = "dataset_records")
public class DatasetRecord {
    @Id
    public String id;

    @NotNull
    @Field(name = "dataset_id")
    public String datasetId;

    @NotNull
    @Field(name = "agent_id")
    public String agentId;

    @NotNull
    @Field(name = "run_id")
    public String runId;

    @Field(name = "data")
    public String data;

    @NotNull
    @Field(name = "run_started_at")
    public ZonedDateTime runStartedAt;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;
}
