package ai.core.api.server.trace;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class TraceAccountView {
    @Property(name = "userId")
    public String userId;

    @Property(name = "name")
    public String name;

    @Property(name = "email")
    public String email;

    @Property(name = "role")
    public String role;

    @Property(name = "status")
    public String status;
}
