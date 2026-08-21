package ai.core.api.server.artifact;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

public class ListSharedArtifactUsersResponse {
    @NotNull
    @Property(name = "users")
    public List<SharedArtifactUserView> users;
}
