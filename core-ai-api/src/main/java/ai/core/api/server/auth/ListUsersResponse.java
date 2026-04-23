package ai.core.api.server.auth;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public class ListUsersResponse {
    @Property(name = "users")
    public List<UserStatusView> users;

    public static class UserStatusView {
        @Property(name = "email")
        public String email;

        @Property(name = "name")
        public String name;

        @Property(name = "role")
        public String role;

        @Property(name = "status")
        public String status;

        @Property(name = "created_at")
        public ZonedDateTime createdAt;
    }
}
