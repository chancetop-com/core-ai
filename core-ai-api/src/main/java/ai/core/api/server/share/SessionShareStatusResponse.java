package ai.core.api.server.share;

import core.framework.api.json.Property;
import java.util.List;

/**
 * @author Xander
 */
public class SessionShareStatusResponse {
    @Property(name = "share")
    public SessionShareView share;

    @Property(name = "artifacts")
    public List<SharedSessionArtifact> artifacts;
}
