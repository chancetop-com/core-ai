package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
@Collection(name = "datasets")
public class Dataset {
    @Id
    public String id;

    @NotNull
    @Field(name = "name")
    public String name;

    @Field(name = "description")
    public String description;

    @NotNull
    @Field(name = "user_id")
    public String userId;

    @Field(name = "schema")
    public List<SchemaField> schema;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @NotNull
    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
