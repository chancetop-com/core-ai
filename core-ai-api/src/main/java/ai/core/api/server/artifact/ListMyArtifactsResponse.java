package ai.core.api.server.artifact;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

public class ListMyArtifactsResponse {
    @NotNull
    @Property(name = "total")
    public Long total;

    @NotNull
    @Property(name = "artifacts")
    public List<MyArtifactView> artifacts;
}
