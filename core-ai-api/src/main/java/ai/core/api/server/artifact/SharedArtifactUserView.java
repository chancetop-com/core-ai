package ai.core.api.server.artifact;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

public class SharedArtifactUserView {
    @NotNull
    @Property(name = "user_id")
    public String userId;

    @NotNull
    @Property(name = "name")
    public String name;
}
