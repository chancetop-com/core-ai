package ai.core.api.server.hub;

import core.framework.api.json.Property;

import java.util.List;

/**
 * The datasets a session may reach, with the permission the session holds on each of them. Read-only:
 * dataset definitions are managed by the admin surface, never through this contract.
 *
 * @author stephen
 */
public class HubDatasetListView {
    @Property(name = "session_id")
    public String sessionId;

    @Property(name = "datasets")
    public List<HubDatasetView> datasets;
}
