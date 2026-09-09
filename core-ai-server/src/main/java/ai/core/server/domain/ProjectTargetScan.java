package ai.core.server.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * "Offered to the attributor" marker of one member record within one project. Whether the attributor
 * attributed the target or skipped it as unclear, the record is not offered again unless it GROWS
 * (its material time moves past {@code material_at}, e.g. a session receives new messages). This is
 * what makes the attribution stage convergent: every record costs at most one LLM pass per version.
 *
 * @author stephen
 */
@Collection(name = "project_target_scans")
public class ProjectTargetScan {
    @Id
    public String id;

    @Field(name = "project_id")
    public String projectId;

    // session | run | workflow_run
    @Field(name = "target_type")
    public String targetType;

    @Field(name = "target_id")
    public String targetId;

    // material time of the record when it was offered (session.last_message_at / run.started_at)
    @Field(name = "material_at")
    public ZonedDateTime materialAt;

    @Field(name = "scanned_at")
    public ZonedDateTime scannedAt;
}
