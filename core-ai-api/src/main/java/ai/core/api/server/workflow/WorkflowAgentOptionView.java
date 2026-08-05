package ai.core.api.server.workflow;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;

/**
 * @author Xander
 */
public class WorkflowAgentOptionView {
    @NotNull
    @Property(name = "id")
    public String id;

    @NotNull
    @Property(name = "name")
    public String name;

    @NotNull
    @Property(name = "type")
    public String type;

    @NotNull
    @Property(name = "status")
    public String status;

    @NotNull
    @Property(name = "ownership")
    public String ownership;

    @NotNull
    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
