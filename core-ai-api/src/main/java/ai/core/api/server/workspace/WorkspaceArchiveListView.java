package ai.core.api.server.workspace;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class WorkspaceArchiveListView {
    @NotNull
    @Property(name = "items")
    public List<WorkspaceArchiveView> items = List.of();
}
