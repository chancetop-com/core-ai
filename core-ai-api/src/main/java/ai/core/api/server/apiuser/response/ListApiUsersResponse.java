package ai.core.api.server.apiuser.response;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class ListApiUsersResponse {
    @Property(name = "users")
    public List<AdminApiUserView> users;
}
