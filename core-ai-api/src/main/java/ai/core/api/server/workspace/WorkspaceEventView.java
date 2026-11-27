package ai.core.api.server.workspace;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class WorkspaceEventView {
    @NotNull
    @Property(name = "id")
    public String id;

    @NotNull
    @Property(name = "event_type")
    public String eventType;

    @Property(name = "actor")
    public String actor;

    /** JSON text; never contains file contents or secret material */
    @Property(name = "payload")
    public String payload;

    @NotNull
    @Property(name = "created_at")
    public ZonedDateTime createdAt;
}
