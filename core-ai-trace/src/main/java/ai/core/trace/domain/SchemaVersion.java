package ai.core.trace.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * @author Xander
 */
@Collection(name = "schema_versions")
public class SchemaVersion {
    @Id
    public String id;

    @Field(name = "version")
    public String version;

    @Field(name = "description")
    public String description;

    @Field(name = "applied_at")
    public ZonedDateTime appliedAt;
}
