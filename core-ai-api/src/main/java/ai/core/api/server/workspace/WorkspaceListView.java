package ai.core.api.server.workspace;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class WorkspaceListView {
    @NotNull
    @Property(name = "items")
    public List<WorkspaceView> items;
}
