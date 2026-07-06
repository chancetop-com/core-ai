package ai.core.server.sandbox.snapshot;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * Per-session sandbox instance counter. Incremented on every sandbox acquire;
 * a capture whose recorded epoch no longer matches is stale and must be discarded.
 * The session id is the _id, so lookups never need a secondary index.
 *
 * @author xander
 */
@Collection(name = "sandbox_epochs")
public class SandboxEpochDoc {
    @Id
    public String id;

    @NotNull
    @Field(name = "epoch")
    public Long epoch;

    @NotNull
    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
