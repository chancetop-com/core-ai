package ai.core.api.server.workflow;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author Xander
 */
public class ListWorkflowAgentOptionsResponse {
    @NotNull
    @Property(name = "items")
    public List<WorkflowAgentOptionView> items;

    @Property(name = "selected")
    public WorkflowAgentOptionView selected;

    @NotNull
    @Property(name = "total")
    public Long total;

    @NotNull
    @Property(name = "page")
    public Integer page;

    @NotNull
    @Property(name = "limit")
    public Integer limit;
}
