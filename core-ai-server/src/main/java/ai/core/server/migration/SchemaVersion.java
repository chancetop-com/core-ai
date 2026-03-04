package ai.core.server.migration;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
@Collection(name = "schema_versions")
public class SchemaVersion {
    @Id
    public String id;

    @NotNull
    @Field(name = "description")
    public String description;

    @NotNull
    @Field(name = "applied_at")
    public ZonedDateTime appliedAt;
}
