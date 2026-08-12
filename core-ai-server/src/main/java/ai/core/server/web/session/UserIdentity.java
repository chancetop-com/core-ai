package ai.core.server.web.session;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * User identity stored in the core-ng session (key {@code USER_IDENTITY}),
 * mirroring the fbr-project location-hub session permission pattern.
 * {@code permissions} are the resolved RBAC action codes (admin = ["*"]);
 * {@code expiredAt} marks when the cached permissions should be re-resolved.
 *
 * @author stephen
 */
public class UserIdentity {
    public String userId;
    public String name;
    public String role;
    public List<String> permissions;
    public ZonedDateTime expiredAt;
}
