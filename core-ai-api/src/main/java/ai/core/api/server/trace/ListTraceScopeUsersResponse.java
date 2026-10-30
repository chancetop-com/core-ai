package ai.core.api.server.trace;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Users the current caller may filter traces by (its resolved trace data scope).
 *
 * @author stephen
 */
public class ListTraceScopeUsersResponse {
    @Property(name = "users")
    public List<TraceAccountView> users;
}
